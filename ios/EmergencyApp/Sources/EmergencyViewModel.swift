import BeaconRadio
import Combine
import DeviceIdentity
import Foundation
import NetworkRoleMachine
import PacketCodec
import ParticipantRegistration
import PersonStateMachine

/// Limitación conocida: el estado vive solo en memoria dentro de esta
/// instancia. La recuperación tardía desde `SILENCIO_TIMEOUT` funciona
/// mientras la app siga viva (probado en simulador), pero no sobrevive a un
/// cierre real del proceso — persistir el estado entre lanzamientos (para
/// que "abrir la app más tarde" también cubra ese caso) queda fuera de esta
/// ticket; es una decisión de diseño propia (dónde guardarlo, cómo re-armar
/// los timers en curso) que merece su propio issue.
///
/// Limitación conocida (#6/#8): la interoperabilidad real entre 3 teléfonos
/// (A→B→C con TTL reducido en 1 por salto) no se verificó en esta sesión
/// por falta de dispositivos físicos — eso es exactamente lo que cubre la
/// ticket #10 (verificación de campo). Aquí se confirmó que
/// advertising/scanning/rotación arrancan sin error en el simulador y que
/// el pipeline decode→dedup→relay→log es correcto (cubierto por los tests
/// de `BeaconRadio`). El emisor de iOS usa GATT en vez de Manufacturer Data
/// para transmitir — ver `BeaconGattService` para el porqué (revisión de la
/// decisión 2 del spec).
@MainActor
final class EmergencyViewModel: ObservableObject {
    @Published private(set) var state: PersonState = .dormido
    @Published private(set) var networkRole: NetworkRole = .apagado
    @Published private(set) var logEntries: [LogEntry] = []
    /// Cuenta regresiva visible durante el sacudón (timer de gracia) y
    /// durante la ventana de confirmación — la misma UI sirve para ambas,
    /// solo cambia la duración de la que arranca.
    @Published private(set) var countdownSecondsRemaining: Int?

    private let machine: PersonStateMachine
    private let networkMachine = NetworkRoleMachine()
    private let shakeDuration: TimeInterval
    private let confirmationWindow: TimeInterval
    private var countdownDeadline: Date?
    private var countdownTimer: Timer?

    private let deviceIdHash: Data
    private let dedupCache = DedupCache()
    private let nonceGenerator: NonceGenerating = RandomNonceGenerator()
    private let advertiser = BleAdvertiser()
    private let scanner = BleScanner()
    private let relayQueue: RelayQueue
    private let batteryMonitor = BatteryMonitor()
    private let connectivityMonitor = ConnectivityMonitor()
    private let participantUploadCoordinator: ParticipantUploadCoordinator
    private let meshStateRegistry = MeshStateRegistry()
    private let gatewayUploader: GatewayUploader
    /// Caso A (#52): `verifiedIdentityRegistry.record(_:)` todavía no lo
    /// llama nada — ninguna ticket de Fase 4 conectó todavía la verificación
    /// local de fragmentos (`SignatureFragmentAssembler`, #44) a la recepción
    /// real de paquetes BLE (`handleReceivedPacketData` solo decodifica
    /// `BeaconPacket`, layout legado `Tipo=0-2`, hoy). Instanciar el
    /// assembler acá antes de que exista esa fuente real sería código muerto
    /// (Speculative Generality) — a diferencia de `identityConfirmationUploader`,
    /// que sí está atado a una señal real que ya funciona (`GATEWAY_ACTIVO`).
    /// Cuando una ticket futura resuelva ese dispatch, le toca a ella también
    /// instanciar el assembler y conectar `onIdentityVerified` acá.
    private let verifiedIdentityRegistry = VerifiedIdentityRegistry()
    private let identityConfirmationUploader: IdentityConfirmationUploader

    /// Duraciones cortas por defecto para poder demostrar el flujo completo
    /// sin esperar minutos reales. En un dispositivo real se usarían ~120s
    /// de gracia y una ventana de 15-30 min — ambas configurables aquí mismo.
    init(shakeDuration: TimeInterval = 3, confirmationWindow: TimeInterval = 20) {
        self.shakeDuration = shakeDuration
        self.confirmationWindow = confirmationWindow
        let ownPublicKeyEd25519 = KeychainDeviceIdentity.publicKeyEd25519()
        let ownDeviceIdHash = DeviceIdentityHash.fromPublicKey(ownPublicKeyEd25519)
        deviceIdHash = ownDeviceIdHash
        // Mitigación Sybil de Caso A (#50): costo único al instalar, no debe
        // competir con el hilo principal ni con batería en una emergencia —
        // se dispara en background y se persiste; una corrida posterior la
        // encuentra ya calculada y no repite el trabajo. Captura el hash en
        // una constante local (no `self.deviceIdHash`) porque `self` todavía
        // no termina de inicializarse en este punto del `init`.
        Task.detached(priority: .utility) {
            _ = KeychainDeviceIdentity.proofOfWorkSeal(deviceIdHash: ownDeviceIdHash)
        }
        participantUploadCoordinator = ParticipantUploadCoordinator(
            deviceIdHash: deviceIdHash,
            publicKeyEd25519: ownPublicKeyEd25519,
            uploader: FirebaseParticipantUploader(),
            pendingProfile: KeychainParticipantStore.pendingProfile()
        )
        participantUploadCoordinator.onUploadSucceeded = {
            KeychainParticipantStore.markUploaded()
        }
        gatewayUploader = GatewayUploader(registry: meshStateRegistry, uploader: FirebaseMeshStateUploader())
        identityConfirmationUploader = IdentityConfirmationUploader(
            registry: verifiedIdentityRegistry,
            uploader: FirebaseIdentityConfirmationUploader()
        )
        let scheduler = RealScheduler()
        machine = PersonStateMachine(
            scheduler: scheduler,
            shakeDuration: shakeDuration,
            confirmationWindow: confirmationWindow
        )
        relayQueue = RelayQueue(scheduler: scheduler)
        appendLogEntry(.info(message: "device_id_hash propio: \(deviceIdHash.shortHex)"))
        appendLogEntry(.transition(state: machine.state, sequence: machine.sequence))
        machine.onTransition = { [weak self] newState in
            self?.handleTransition(to: newState)
        }
        wireRadio()
        refreshAdvertisedBeacon()
        relayQueue.start()

        networkMachine.onTransition = { [weak self] newRole in
            self?.handleNetworkRoleTransition(to: newRole)
        }
        wireNetworkMonitors()
        // La app está en primer plano desde que se crea este view model — la
        // Máquina B no requiere confirmación explícita del usuario para
        // salir de `APAGADO`, a diferencia de la Máquina A.
        networkMachine.appActivated()
    }

    func simulateEarthquake() { machine.simulateEarthquake() }
    func confirmOk() { machine.confirmOk() }
    func requestHelp() { machine.requestHelp() }

    // MARK: - Máquina B (rol de red, tickets #13/#17/#20/#24)
    //
    // Batería y conectividad llegan de `BatteryMonitor`/`ConnectivityMonitor`
    // (señales reales del sistema, ver `wireNetworkMonitors`) y "pendiente
    // de sincronizar" llega de `relayQueue.onForeignQueuePendingChanged`
    // (tickets #18/#19). Sin disparadores manuales — #22 verificó en
    // hardware que las señales reales bastan.

    /// Traduce las señales reales del sistema operativo a la Máquina B, y
    /// las transiciones de la Máquina B de vuelta a `RelayQueue` (ticket
    /// #17/#20): `BAJO_CONSUMO` activa la prioridad de descarte, y
    /// `GATEWAY_ACTIVO` arma/retira el anuncio de gateway.
    private func wireNetworkMonitors() {
        gatewayUploader.onError = onMain { viewModel, error in
            viewModel.appendLogEntry(.info(message: error.localizedDescription))
        }
        identityConfirmationUploader.onError = onMain { viewModel, error in
            viewModel.appendLogEntry(.info(message: error.localizedDescription))
        }
        batteryMonitor.onBatteryChanged = onMain { viewModel, reading in
            viewModel.networkMachine.updateBattery(percent: reading.percent, isCharging: reading.isCharging)
        }
        connectivityMonitor.onConnectivityChanged = onMain { viewModel, hasConnectivity in
            guard hasConnectivity else { return }
            viewModel.networkMachine.connectivityDetected()
            viewModel.participantUploadCoordinator.connectivityDetected()
        }
        // `RelayQueue` ya despacha en el hilo principal (ver `wireRadio`),
        // así que esto no necesita pasar por `onMain`.
        relayQueue.onForeignQueuePendingChanged = { [weak self] isPending in
            guard let self else { return }
            if isPending {
                self.networkMachine.somethingPendingToSync()
            } else {
                self.networkMachine.nothingPendingToSync()
            }
        }
        batteryMonitor.start()
        connectivityMonitor.start()
    }

    private func handleNetworkRoleTransition(to newRole: NetworkRole) {
        networkRole = newRole
        appendLogEntry(.networkRoleTransition(role: newRole))
        relayQueue.isLowPower = (newRole == .bajoConsumo)
        if newRole == .gatewayActivo {
            refreshGatewayAnnouncement()
            gatewayUploader.start()
            identityConfirmationUploader.start()
        } else {
            relayQueue.clearGatewayAnnouncement()
            gatewayUploader.stop()
            identityConfirmationUploader.stop()
        }
    }

    /// Arma el anuncio de gateway a partir del estado actual y lo pone en
    /// su slot fijo en `RelayQueue`. Se auto-registra en la propia caché de
    /// dedup al emitirlo (decisión 12, igual que `refreshAdvertisedBeacon`):
    /// si este anuncio rebota de un vecino, se descarta como duplicado por
    /// el mismo camino que cualquier otro paquete, en vez de colarse en
    /// `foreignEntries` como si fuera ajeno.
    private func refreshGatewayAnnouncement() {
        let packet = LocalBeaconFactory.makeGatewayAnnouncement(
            deviceIdHash: deviceIdHash,
            sequence: machine.sequence,
            now: Date(),
            nonceGenerator: nonceGenerator
        )
        dedupCache.insertIfAbsent(DedupCache.Key(deviceIdHash: packet.deviceIdHash, nonce: packet.nonce))
        relayQueue.updateGatewayAnnouncement(packet)
    }

    private func handleTransition(to newState: PersonState) {
        state = newState
        appendLogEntry(.transition(state: newState, sequence: machine.sequence))
        refreshAdvertisedBeacon()

        switch newState {
        case .activoSinConfirmar:
            startCountdown(duration: shakeDuration)
        case .esperandoConfirmacion:
            startCountdown(duration: confirmationWindow)
        default:
            stopCountdown()
        }
    }

    private func appendLogEntry(_ kind: LogEntry.Kind) {
        logEntries.append(LogEntry(timestamp: Date(), kind: kind))
    }

    // MARK: - BLE (advertising + scanning + dedup + relay, tickets #6/#8)

    private func wireRadio() {
        advertiser.onError = onMain { viewModel, message in viewModel.appendLogEntry(.info(message: message)) }
        scanner.onError = onMain { viewModel, message in viewModel.appendLogEntry(.info(message: message)) }
        // Manufacturer Data directa (p. ej. peers Android, que sí pueden
        // anunciarla): se decodifica con el mismo envoltorio con el que se
        // emite. GATT (peers iOS, ver `BeaconGattService`): el valor leído
        // ya es el `BeaconPacket` crudo, sin envoltorio.
        scanner.onManufacturerData = onMain { viewModel, data in
            viewModel.handleReceivedPacketData(data, decode: ManufacturerDataFrame.decode)
        }
        scanner.onGattPacketData = onMain { viewModel, data in
            viewModel.handleReceivedPacketData(data, decode: BeaconPacketCodec.decode)
        }
        // `RelayQueue` ya despacha en el hilo principal (su scheduler es el
        // mismo `RealScheduler` de la Máquina A), así que esto no necesita
        // pasar por `onMain`.
        relayQueue.onCurrentPacketChanged = { [weak self] packet in
            self?.advertiser.updateAdvertisedData(BeaconPacketCodec.encode(packet))
        }
        scanner.start()
    }

    /// Reenvía un callback de un envoltorio de framework (`BleAdvertiser`,
    /// `BleScanner`, `BatteryMonitor`, `ConnectivityMonitor`; puede llegar
    /// desde cualquier hilo) de vuelta al `MainActor`, sin repetir el mismo
    /// `[weak self] in Task { @MainActor in ... }` en cada call site.
    private func onMain<Value>(_ work: @escaping (EmergencyViewModel, Value) -> Void) -> (Value) -> Void {
        { [weak self] value in
            guard let self else { return }
            Task { @MainActor in work(self, value) }
        }
    }

    /// Reconstruye el `BeaconPacket` local a partir del estado actual de la
    /// Máquina A y lo pone en la cola de relay (decisión 9): el propio
    /// beacon siempre está presente ahí, junto a los beacons ajenos
    /// pendientes de retransmitir, rotando en round-robin. Se auto-registra
    /// en la propia caché de dedup al emitir (decisión 12): si este mismo
    /// beacon rebota de un vecino, se descarta como duplicado por el mismo
    /// camino que cualquier otro, sin una ruta de código especial para "es
    /// mío" — pero eso también significa que `handleReceivedPacketData`
    /// nunca lo ve, así que `meshStateRegistry` se alimenta acá también
    /// (#31): sin esto, un teléfono en `GATEWAY_ACTIVO` subiría el estado de
    /// otros pero nunca el propio.
    private func refreshAdvertisedBeacon() {
        let packet = LocalBeaconFactory.makeBeacon(
            deviceIdHash: deviceIdHash,
            status: machine.status,
            sequence: machine.sequence,
            now: Date(),
            nonceGenerator: nonceGenerator
        )
        dedupCache.insertIfAbsent(DedupCache.Key(deviceIdHash: packet.deviceIdHash, nonce: packet.nonce))
        relayQueue.updateOwnBeacon(packet)
        meshStateRegistry.update(with: packet)
    }

    /// Punto de entrada compartido por ambas rutas de recepción (Manufacturer
    /// Data directa y GATT) — cada una trae su propio `decode` porque el
    /// envoltorio de bytes difiere, pero el dedup + log + relay de ahí en
    /// adelante es idéntico sin importar el transporte.
    private func handleReceivedPacketData(_ data: Data, decode: (Data) -> BeaconPacket?) {
        guard let packet = decode(data) else { return }
        let key = DedupCache.Key(deviceIdHash: packet.deviceIdHash, nonce: packet.nonce)
        guard dedupCache.insertIfAbsent(key) else {
            appendLogEntry(.duplicateDiscarded(deviceIdHash: packet.deviceIdHash, nonce: packet.nonce))
            return
        }
        appendLogEntry(.beaconReceived(deviceIdHash: packet.deviceIdHash, ttl: packet.ttl, sequence: packet.sequence))
        meshStateRegistry.update(with: packet)
        if let relayed = RelayPolicy.decrementedForRelay(packet) {
            relayQueue.enqueueForeignBeacon(relayed)
        } else {
            appendLogEntry(.ttlExhausted(deviceIdHash: packet.deviceIdHash, sequence: packet.sequence))
        }
    }

    private func startCountdown(duration: TimeInterval) {
        countdownDeadline = Date().addingTimeInterval(duration)
        countdownSecondsRemaining = Int(duration)
        countdownTimer?.invalidate()
        countdownTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tickCountdown() }
        }
    }

    private func tickCountdown() {
        guard let deadline = countdownDeadline else { return }
        let remaining = Int(ceil(deadline.timeIntervalSinceNow))
        countdownSecondsRemaining = max(remaining, 0)
        if remaining <= 0 { stopCountdown() }
    }

    private func stopCountdown() {
        countdownTimer?.invalidate()
        countdownTimer = nil
        countdownDeadline = nil
        countdownSecondsRemaining = nil
    }
}
