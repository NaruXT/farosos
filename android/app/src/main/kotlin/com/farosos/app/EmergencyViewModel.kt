package com.farosos.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.farosos.beaconradio.DedupCache
import com.farosos.beaconradio.GatewayUploader
import com.farosos.beaconradio.IdentityConfirmationUploader
import com.farosos.beaconradio.LocalBeaconFactory
import com.farosos.beaconradio.MeshParticipantState
import com.farosos.beaconradio.MeshStateRegistry
import com.farosos.beaconradio.RandomNonceGenerator
import com.farosos.beaconradio.RelayPolicy
import com.farosos.beaconradio.RelayQueue
import com.farosos.beaconradio.VerifiedIdentityRegistry
import com.farosos.caseresolution.AttendingMark
import com.farosos.caseresolution.CaseResolutionUploadCoordinator
import com.farosos.caseresolution.ResolutionMark
import com.farosos.codec.BeaconPacket
import com.farosos.codec.BeaconPacketCodec
import com.farosos.deviceidentity.DeviceIdentityHash
import com.farosos.networkrole.NetworkRole
import com.farosos.networkrole.NetworkRoleMachine
import com.farosos.participantregistration.ParticipantUploadCoordinator
import com.farosos.personstate.PersonState
import com.farosos.personstate.PersonStateMachine

/**
 * Limitación conocida: el estado vive solo en memoria dentro de esta
 * instancia. La recuperación tardía desde `SILENCIO_TIMEOUT` funciona
 * mientras el proceso siga vivo (probado en el emulador), pero no sobrevive
 * a que el sistema mate el proceso — persistir el estado entre lanzamientos
 * queda fuera de esta ticket; es una decisión de diseño propia (dónde
 * guardarlo, cómo re-armar los timers en curso) que merece su propio issue.
 *
 * Limitación conocida (#7): la interoperabilidad real de las dos radios BLE
 * (advertising + scanning entre dos teléfonos distintos) no se verificó en
 * esta sesión por falta de un segundo dispositivo físico — eso es
 * exactamente lo que cubre la ticket #10 (verificación de campo A→B→C).
 * El radio arranca recién cuando `startRadioIfNotStarted()` confirma que
 * los permisos de Bluetooth ya fueron concedidos (ver `MainActivity`).
 *
 * A diferencia de iOS (donde el propio sistema strippea Manufacturer Data
 * en background, dejando "foreground-only" gratis), Android no detiene
 * advertising/scanning por su cuenta — `MainActivity` reenvía los eventos
 * de ciclo de vida `ON_START`/`ON_STOP` a `onAppForegrounded`/
 * `onAppBackgrounded` para cumplir ese requisito explícitamente.
 */
class EmergencyViewModel @JvmOverloads constructor(
    application: Application,
    private val shakeDuration: Double = 3.0,
    private val confirmationWindow: Double = 20.0
) : AndroidViewModel(application) {
    var state by mutableStateOf(PersonState.DORMIDO)
        private set
    var networkRole by mutableStateOf(NetworkRole.APAGADO)
        private set
    val logEntries = mutableStateListOf<LogEntry>()
    var countdownSecondsRemaining by mutableStateOf<Int?>(null)
        private set

    /**
     * Casos `AYUDA_SOLICITADA`/`SILENCIO_TIMEOUT` de otros participantes
     * que este teléfono ya conoce por la malla (#55) — excluye siempre el
     * propio caso. Recalculada tras cada `meshStateRegistry.update(...)`
     * en vez de colgarse de `meshStateRegistry.onStateUpdated` (ese slot ya
     * lo usa `gatewayUploader` para subir al backend; reasignarlo acá lo
     * pisaría según el rol de red actual).
     */
    val knownCases = mutableStateListOf<MeshParticipantState>()

    private val handler = Handler(Looper.getMainLooper())
    private val scheduler = RealScheduler()
    private val machine = PersonStateMachine(scheduler, shakeDuration, confirmationWindow)
    private val networkMachine = NetworkRoleMachine()
    private var countdownDeadlineMillis: Long? = null
    private var countdownRunnable: Runnable? = null

    private val ownPublicKeyEd25519: ByteArray = DeviceIdentity.publicKeyEd25519(application)
    private val deviceIdHash: ByteArray = DeviceIdentityHash.fromPublicKey(ownPublicKeyEd25519)
    private val dedupCache = DedupCache()
    private val nonceGenerator = RandomNonceGenerator()
    private val advertiser = BleAdvertiser(application)
    private val scanner = BleScanner(application)
    private val relayQueue = RelayQueue(scheduler)
    private val batteryMonitor = BatteryMonitor(application)
    private val connectivityMonitor = ConnectivityMonitor(application)
    private val participantUploadCoordinator = ParticipantUploadCoordinator(
        deviceIdHash = deviceIdHash,
        publicKeyEd25519 = ownPublicKeyEd25519,
        uploader = FirebaseParticipantUploader(),
        pendingProfile = ParticipantStore.pendingProfile(application)
    )
    private val meshStateRegistry = MeshStateRegistry()
    private val gatewayUploader = GatewayUploader(meshStateRegistry, FirebaseMeshStateUploader())
    private val caseResolutionUploadCoordinator = CaseResolutionUploadCoordinator(
        resolverDeviceIdHash = deviceIdHash,
        uploader = FirebaseCaseResolutionUploader(),
        pendingResolutions = CaseResolutionStore.pendingResolutions(application),
        pendingAttending = CaseResolutionStore.pendingAttending(application)
    )
    // Caso A (#53): `verifiedIdentityRegistry.record(_)` todavía no lo llama
    // nada — ninguna ticket de Fase 4 conectó todavía la verificación local
    // de fragmentos (`SignatureFragmentAssembler`, #45) a la recepción real
    // de paquetes BLE (`handleReceivedPacketData` solo decodifica
    // `BeaconPacket`, layout legado Tipo=0-2, hoy). Instanciar el assembler
    // acá antes de que exista esa fuente real sería código muerto
    // (Speculative Generality) — a diferencia de `identityConfirmationUploader`,
    // que sí está atado a una señal real que ya funciona (`GATEWAY_ACTIVO`).
    // Cuando una ticket futura resuelva ese dispatch, le toca a ella también
    // instanciar el assembler y conectar `onIdentityVerified` acá.
    private val verifiedIdentityRegistry = VerifiedIdentityRegistry()
    private val identityConfirmationUploader = IdentityConfirmationUploader(verifiedIdentityRegistry, FirebaseIdentityConfirmationUploader())
    private var radioStarted = false
    private var isForeground = false

    init {
        // Mitigación Sybil de Caso A (#51): costo único al instalar, no debe
        // competir con el hilo principal ni con batería en una emergencia —
        // se dispara en un hilo aparte y se persiste; una corrida posterior
        // la encuentra ya calculada y no repite el trabajo.
        Thread { DeviceIdentity.proofOfWorkSeal(application, deviceIdHash) }.start()
        participantUploadCoordinator.onUploadSucceeded = { ParticipantStore.markUploaded(application) }
        caseResolutionUploadCoordinator.onResolutionUploaded = { mark -> CaseResolutionStore.removePendingResolution(mark, application) }
        caseResolutionUploadCoordinator.onAttendingUploaded = { mark -> CaseResolutionStore.removePendingAttending(mark, application) }
        appendLogEntry(LogEntry.Kind.Info("device_id_hash propio: ${deviceIdHash.shortHex()}"))
        appendLogEntry(LogEntry.Kind.Transition(machine.state, machine.sequence))
        machine.onTransition = { newState -> handleTransition(newState) }

        networkMachine.onTransition = { newRole -> handleNetworkRoleTransition(newRole) }
        wireNetworkMonitors()
        // La app está en primer plano desde que se crea este view model — la
        // Máquina B no requiere confirmación explícita del usuario para
        // salir de APAGADO, a diferencia de la Máquina A.
        networkMachine.appActivated()
    }

    fun simulateEarthquake() = machine.simulateEarthquake()
    fun confirmOk() = machine.confirmOk()
    fun requestHelp() = machine.requestHelp()

    // --- Máquina B (rol de red, tickets #14/#17/#21/#25) ---
    //
    // Batería y conectividad llegan de `BatteryMonitor`/`ConnectivityMonitor`
    // (señales reales del sistema, ver `wireNetworkMonitors`) y "pendiente
    // de sincronizar" llega de `relayQueue.onForeignQueuePendingChanged`
    // (tickets #18/#19). Sin disparadores manuales — #23 verificó en
    // hardware que las señales reales bastan.

    /**
     * Traduce las señales reales del sistema operativo a la Máquina B, y
     * las transiciones de la Máquina B de vuelta a `RelayQueue` (ticket
     * #17/#21): `BAJO_CONSUMO` activa la prioridad de descarte, y
     * `GATEWAY_ACTIVO` arma/retira el anuncio de gateway.
     */
    private fun wireNetworkMonitors() {
        gatewayUploader.onError = onMain { error -> appendLogEntry(LogEntry.Kind.Info(error.message ?: error.toString())) }
        identityConfirmationUploader.onError = onMain { error -> appendLogEntry(LogEntry.Kind.Info(error.message ?: error.toString())) }
        batteryMonitor.onBatteryChanged = onMain { reading ->
            networkMachine.updateBattery(percent = reading.percent, isCharging = reading.isCharging)
        }
        connectivityMonitor.onConnectivityChanged = onMain { hasConnectivity ->
            if (hasConnectivity) {
                networkMachine.connectivityDetected()
                participantUploadCoordinator.connectivityDetected()
                caseResolutionUploadCoordinator.connectivityDetected()
            }
        }
        // `relayQueue` usa el mismo `Scheduler` (respaldado por el main
        // looper) que la Máquina A, así que este callback ya llega en el
        // hilo principal — no hace falta pasar por `onMain`.
        relayQueue.onForeignQueuePendingChanged = { isPending ->
            if (isPending) networkMachine.somethingPendingToSync() else networkMachine.nothingPendingToSync()
        }
        batteryMonitor.start()
        connectivityMonitor.start()
    }

    private fun handleNetworkRoleTransition(newRole: NetworkRole) {
        networkRole = newRole
        appendLogEntry(LogEntry.Kind.NetworkRoleTransition(newRole))
        relayQueue.isLowPower = newRole == NetworkRole.BAJO_CONSUMO
        if (newRole == NetworkRole.GATEWAY_ACTIVO) {
            refreshGatewayAnnouncement()
            gatewayUploader.start()
            identityConfirmationUploader.start()
        } else {
            relayQueue.clearGatewayAnnouncement()
            gatewayUploader.stop()
            identityConfirmationUploader.stop()
        }
    }

    /**
     * Arma el anuncio de gateway a partir del estado actual y lo pone en su
     * slot fijo en `relayQueue`. Se auto-registra en la propia caché de
     * dedup al emitirlo (decisión 12, igual que `refreshAdvertisedBeacon`):
     * si este anuncio rebota de un vecino, se descarta como duplicado por
     * el mismo camino que cualquier otro paquete, en vez de colarse en las
     * entradas ajenas como si fuera de otro nodo.
     */
    private fun refreshGatewayAnnouncement() {
        val packet = LocalBeaconFactory.makeGatewayAnnouncement(
            deviceIdHash = deviceIdHash,
            sequence = machine.sequence,
            nowEpochSeconds = System.currentTimeMillis() / 1000,
            nonceGenerator = nonceGenerator
        )
        dedupCache.insertIfAbsent(DedupCache.Key(packet.deviceIdHash, packet.nonce))
        relayQueue.updateGatewayAnnouncement(packet)
    }

    /**
     * Arranca advertising + scanning por primera vez. Se llama desde
     * `MainActivity` recién cuando el usuario ya concedió los permisos de
     * Bluetooth requeridos — llamarlo antes lanzaría `SecurityException`
     * en Android 12+.
     */
    fun startRadioIfNotStarted() {
        if (radioStarted) return
        radioStarted = true
        wireRadio()
        onAppForegrounded()
    }

    /** Reanuda advertising + scanning — `MainActivity` lo llama en `ON_START`. */
    fun onAppForegrounded() {
        if (!radioStarted || isForeground) return
        isForeground = true
        scanner.start()
        refreshAdvertisedBeacon()
        relayQueue.start()
    }

    /**
     * Detiene advertising + scanning — `MainActivity` lo llama en `ON_STOP`
     * para cumplir el requisito de operación foreground-only. `relayQueue`
     * también se detiene aquí: si siguiera rotando en background, su
     * callback volvería a llamar `advertiser.updateAdvertisedData` y
     * reanudaría el advertising que este método acaba de apagar.
     */
    fun onAppBackgrounded() {
        if (!isForeground) return
        isForeground = false
        relayQueue.stop()
        scanner.stop()
        advertiser.stop()
    }

    private fun handleTransition(newState: PersonState) {
        state = newState
        appendLogEntry(LogEntry.Kind.Transition(newState, machine.sequence))
        if (isForeground) refreshAdvertisedBeacon()

        when (newState) {
            PersonState.ACTIVO_SIN_CONFIRMAR -> startCountdown(shakeDuration)
            PersonState.ESPERANDO_CONFIRMACION -> startCountdown(confirmationWindow)
            else -> stopCountdown()
        }
    }

    private fun appendLogEntry(kind: LogEntry.Kind) {
        logEntries.add(LogEntry(System.currentTimeMillis(), kind))
    }

    /** Recalcula `knownCases` desde `meshStateRegistry` — ver el comentario de `knownCases`. */
    private fun refreshKnownCases() {
        knownCases.clear()
        knownCases.addAll(
            meshStateRegistry.allStates().filter { known ->
                !known.deviceIdHash.contentEquals(deviceIdHash) &&
                    (known.status == BeaconPacket.Status.AYUDA || known.status == BeaconPacket.Status.SILENCIO_TIMEOUT)
            }
        )
    }

    /**
     * Marca "resuelto" (#55) sobre el caso de otro participante — la UI
     * (`CaseResolutionScreen`) es responsable de no ofrecer esta acción
     * sobre el propio caso ni mientras el propio estado esté pidiendo
     * ayuda. Ubicación propia en 0: sin captura de GPS todavía, misma
     * limitación conocida que `LocalBeaconFactory` para el beacon propio.
     */
    fun markCaseResolved(case: MeshParticipantState) {
        val mark = ResolutionMark(
            victimDeviceIdHash = case.deviceIdHash,
            victimSequence = case.sequence,
            resolverLatitudeE7 = 0,
            resolverLongitudeE7 = 0,
            markedAtEpochSeconds = System.currentTimeMillis() / 1000
        )
        CaseResolutionStore.addPendingResolution(mark, getApplication())
        caseResolutionUploadCoordinator.markResolved(mark)
    }

    /** Marca "atendiendo" (#55, "voy a socorrer") — mismas restricciones de UI que [markCaseResolved]. */
    fun markCaseAttending(case: MeshParticipantState) {
        val mark = AttendingMark(
            victimDeviceIdHash = case.deviceIdHash,
            victimSequence = case.sequence,
            markedAtEpochSeconds = System.currentTimeMillis() / 1000
        )
        CaseResolutionStore.addPendingAttending(mark, getApplication())
        caseResolutionUploadCoordinator.markAttending(mark)
    }

    // --- BLE (advertising + scanning + dedup, ticket #7) ---

    private fun wireRadio() {
        advertiser.onError = onMain { message -> appendLogEntry(LogEntry.Kind.Info(message)) }
        scanner.onError = onMain { message -> appendLogEntry(LogEntry.Kind.Info(message)) }
        // Manufacturer Data directa (p. ej. peers Android, que sí pueden
        // anunciarla) y GATT (peers iOS, ticket #11 — ver `BleScanner`)
        // traen los mismos 26 bytes sin envoltorio adicional en ambos
        // casos, así que ambas rutas convergen en el mismo pipeline de
        // dedup + log + relay.
        scanner.onManufacturerData = onMain { data -> handleReceivedPacketData(data) }
        scanner.onGattPacketData = onMain { data -> handleReceivedPacketData(data) }
        // `relayQueue` usa el mismo `Scheduler` (respaldado por el main
        // looper) que la Máquina A, así que este callback ya llega en el
        // hilo principal — no hace falta pasar por `onMain`.
        relayQueue.onCurrentPacketChanged = { packet -> advertiser.updateAdvertisedData(BeaconPacketCodec.encode(packet)) }
    }

    /**
     * Reenvía un callback de un envoltorio de framework (`BleAdvertiser`,
     * `BleScanner`, `BatteryMonitor`, `ConnectivityMonitor`; no todos
     * garantizan llegar en el hilo principal — p. ej. `NetworkCallback` de
     * `ConnectivityMonitor` llega en un hilo de Binder) de vuelta al hilo
     * principal — Compose solo debe mutar estado ahí.
     */
    private fun <Value> onMain(work: (Value) -> Unit): (Value) -> Unit =
        { value -> handler.post { work(value) } }

    /**
     * Reconstruye el `BeaconPacket` local a partir del estado actual de la
     * Máquina A y lo pone en la cola de relay (ticket #9): el propio beacon
     * siempre está presente ahí, junto a los beacons ajenos pendientes de
     * retransmitir, rotando en round-robin. Se auto-registra en la propia
     * caché de dedup al emitir (decisión 12): si este mismo beacon rebota
     * de un vecino, se descarta como duplicado por el mismo camino que
     * cualquier otro, sin una ruta de código especial para "es mío" — pero
     * eso también significa que `handleReceivedPacketData` nunca lo ve, así
     * que `meshStateRegistry` se alimenta acá también (#32, mismo fix que
     * #31/iOS aplicado desde el arranque, no como corrección posterior): sin
     * esto, un teléfono en GATEWAY_ACTIVO subiría el estado de otros pero
     * nunca el propio.
     */
    private fun refreshAdvertisedBeacon() {
        val packet = LocalBeaconFactory.makeBeacon(
            deviceIdHash = deviceIdHash,
            status = machine.status,
            sequence = machine.sequence,
            nowEpochSeconds = System.currentTimeMillis() / 1000,
            nonceGenerator = nonceGenerator
        )
        dedupCache.insertIfAbsent(DedupCache.Key(packet.deviceIdHash, packet.nonce))
        relayQueue.updateOwnBeacon(packet)
        meshStateRegistry.update(packet)
        refreshKnownCases()
    }

    /**
     * Punto de entrada compartido por ambas rutas de recepción (Manufacturer
     * Data directa y GATT) — `BeaconPacketCodec.decode` ya filtra por
     * Magic/Versión — lo que no coincide simplemente no decodifica y se
     * ignora aquí, sin entrada de log.
     */
    private fun handleReceivedPacketData(data: ByteArray) {
        val packet = BeaconPacketCodec.decode(data) ?: return
        val key = DedupCache.Key(packet.deviceIdHash, packet.nonce)
        if (!dedupCache.insertIfAbsent(key)) {
            appendLogEntry(LogEntry.Kind.DuplicateDiscarded(packet.deviceIdHash, packet.nonce))
            return
        }
        appendLogEntry(LogEntry.Kind.BeaconReceived(packet.deviceIdHash, packet.ttl, packet.sequence))
        meshStateRegistry.update(packet)
        refreshKnownCases()
        val relayed = RelayPolicy.decrementedForRelay(packet)
        if (relayed != null) {
            relayQueue.enqueueForeignBeacon(relayed)
        } else {
            appendLogEntry(LogEntry.Kind.TtlExhausted(packet.deviceIdHash, packet.sequence))
        }
    }

    private fun startCountdown(durationSeconds: Double) {
        stopCountdown()
        countdownDeadlineMillis = System.currentTimeMillis() + (durationSeconds * 1000).toLong()
        countdownSecondsRemaining = durationSeconds.toInt()

        val runnable = object : Runnable {
            override fun run() {
                tickCountdown()
                if (countdownRunnable === this) {
                    handler.postDelayed(this, 1000)
                }
            }
        }
        countdownRunnable = runnable
        handler.postDelayed(runnable, 1000)
    }

    private fun tickCountdown() {
        val deadline = countdownDeadlineMillis ?: return
        val remainingMillis = deadline - System.currentTimeMillis()
        val remaining = ((remainingMillis + 999) / 1000).toInt()
        countdownSecondsRemaining = maxOf(remaining, 0)
        if (remaining <= 0) stopCountdown()
    }

    private fun stopCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
        countdownDeadlineMillis = null
        countdownSecondsRemaining = null
    }

    override fun onCleared() {
        stopCountdown()
        onAppBackgrounded()
        batteryMonitor.stop()
        connectivityMonitor.stop()
        super.onCleared()
    }
}
