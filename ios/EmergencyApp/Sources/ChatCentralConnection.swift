import BeaconRadio
import CoreBluetooth
import DirectChat
import Foundation

/// Rol rescatista (cliente) del canal de chat directo (#61/#62) — conexión
/// BLE dedicada a un `CBPeripheral` específico ya conocido (elegido desde
/// "Casos"), separada de `BleScanner` (que escanea continuamente y no
/// sostiene conexiones largas). `CBCentralManager` propio a propósito: el
/// flujo de chat (conectar → suscribirse → leer clave del host → escribir
/// la propia → intercambiar mensajes mientras la conexión sigue viva) no
/// se parece al de `BleScanner` (conectar → leer una vez → desconectar),
/// mezclar los dos en el mismo delegate complicaría ambos sin necesidad.
final class ChatCentralConnection: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    var onMessagesReceived: (([ChatMessage]) -> Void)?
    var onConnected: (() -> Void)?
    var onDisconnected: (() -> Void)?
    var onError: ((String) -> Void)?
    /// Diagnóstico temporal de campo (#64) - ver el comentario de `ChatViewModel.debugStatus`.
    var onDebugStatus: ((String) -> Void)?

    private lazy var centralManager = CBCentralManager(delegate: self, queue: nil)
    private let session = ChatClientSession()
    private var targetPeripheral: CBPeripheral?
    private var messageCharacteristic: CBCharacteristic?
    private var guestPublicKeyCharacteristic: CBCharacteristic?

    /// Se guarda hasta que el manager llegue a `poweredOn` — puede que
    /// `connect(to:)` se llame antes de que el radio esté listo.
    private var pendingPeripheralIdentifier: UUID?

    /// Hallazgo de campo real (#64, investigación con PacketLogger): Android
    /// no expone ninguna API pública para fijar una dirección BLE estable en
    /// su advertising del chat (`AdvertisingSetParameters.Builder` no tiene
    /// `setOwnAddressType` en la API pública) - el sistema le asigna una
    /// dirección aleatoria resoluble que puede rotar entre que este teléfono
    /// descubre el caso y que el usuario toca "Abrir chat". `connect()`
    /// sobre un `CBPeripheral` con esa dirección ya vieja queda atascado
    /// para siempre: sin bonding (decisión explícita de #61), CoreBluetooth
    /// no puede resolver una RPA rotada de vuelta a la misma identidad, y
    /// reintenta internamente contra la dirección vieja sin escalar nunca un
    /// error a la app (confirmado con un trace HCI real: `LE Extended
    /// Create Connection` seguido de `LE Create Connection Cancel` cada
    /// pocos segundos, indefinidamente). Mitigación recomendada por un
    /// ingeniero de Apple en foro oficial para conexiones poco confiables:
    /// no reintentar sobre el mismo objeto, re-escanear y conectar sobre una
    /// instancia recién descubierta. Acá se implementa como un escaneo
    /// continuo filtrado al servicio del chat mientras dura el intento,
    /// más un timer que cancela y reconecta con el `CBPeripheral` más
    /// fresco visto hasta ese momento.
    private var targetDeviceIdHash: Data?
    private var latestDiscoveredPeripheral: CBPeripheral?
    private static let retryInterval: TimeInterval = 3

    override init() {
        super.init()
        session.onMessagesReceived = { [weak self] messages in self?.onMessagesReceived?(messages) }
    }

    /// `deviceIdHash` identifica al peer entre los anuncios que el escaneo
    /// propio de esta clase recibe mientras dura el intento - ver el
    /// comentario de arriba.
    func connect(to peripheral: CBPeripheral, deviceIdHash: Data) {
        targetDeviceIdHash = deviceIdHash
        latestDiscoveredPeripheral = peripheral
        let identifier = peripheral.identifier
        guard centralManager.state == .poweredOn else {
            onDebugStatus?("esperando poweredOn (state=\(centralManager.state.rawValue))")
            pendingPeripheralIdentifier = identifier
            return
        }
        connect(identifier: identifier)
    }

    /// Hallazgo de campo real (#64): el `CBPeripheral` que llega acá lo
    /// descubrió el `CBCentralManager` *de `BleScanner`* (otra instancia,
    /// otro delegate) - CoreBluetooth no reconoce ese objeto como propio de
    /// `centralManager`, así que `connect()` sobre él queda colgado para
    /// siempre sin disparar `didConnect` ni `didFailToConnect` (ninguna
    /// señal de error, solo "Conectando…" eterno). El patrón correcto para
    /// reusar un identificador descubierto por otro manager es
    /// `retrievePeripherals(withIdentifiers:)`, que devuelve una instancia
    /// ya scoped al manager que la va a usar.
    private func connect(identifier: UUID) {
        guard let ownPeripheral = centralManager.retrievePeripherals(withIdentifiers: [identifier]).first else {
            onDebugStatus?("retrievePeripherals no encontró \(identifier)")
            onError?("No se pudo recuperar el dispositivo para conectar al chat.")
            return
        }
        targetPeripheral = ownPeripheral
        ownPeripheral.delegate = self
        onDebugStatus?("connect() llamado, managerState=\(centralManager.state.rawValue) peripheral.state=\(ownPeripheral.state.rawValue)")
        centralManager.connect(ownPeripheral)
        centralManager.scanForPeripherals(withServices: [ChatGattService.serviceUUID], options: nil)
        startPolling()
        startRetryTimer()
    }

    private var retryTimer: Timer?

    private func startRetryTimer() {
        retryTimer?.invalidate()
        retryTimer = Timer.scheduledTimer(withTimeInterval: Self.retryInterval, repeats: true) { [weak self] _ in
            self?.retryWithFreshestPeripheral()
        }
    }

    private func retryWithFreshestPeripheral() {
        guard let peripheral = latestDiscoveredPeripheral, peripheral.state != .connected else { return }
        if let targetPeripheral, targetPeripheral.state == .connecting {
            centralManager.cancelPeripheralConnection(targetPeripheral)
        }
        targetPeripheral = peripheral
        peripheral.delegate = self
        onDebugStatus?("retry: reconectando con peripheral más fresco visto")
        centralManager.connect(peripheral)
    }

    /// Filtra por `deviceIdHash` (mismo formato que `ManufacturerDataFrame`:
    /// Company ID de 2 bytes little-endian + payload) para no confundir el
    /// anuncio del chat de este peer con el de otro caso conocido.
    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard let targetDeviceIdHash,
              let manufacturerData = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data,
              let hash = ManufacturerDataFrame.payload(from: manufacturerData),
              hash == targetDeviceIdHash
        else { return }
        latestDiscoveredPeripheral = peripheral
    }

    /// Diagnóstico temporal de campo (#64) - `CBPeripheral.state` es una
    /// propiedad de lectura directa, no depende de que CoreBluetooth
    /// dispare un delegate. Sondearla en vivo distingue "el link nunca
    /// llega a conectar" (state se queda en 0/disconnected) de "conecta
    /// pero el delegate nunca avisa" (state pasa a 2/connected sin que
    /// `didConnect` se dispare).
    private var pollTimer: Timer?

    private func startPolling() {
        pollTimer?.invalidate()
        pollTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self, let peripheral = self.targetPeripheral else { return }
            self.onDebugStatus?("poll: peripheral.state=\(peripheral.state.rawValue)")
        }
    }

    func disconnect() {
        stopRetrying()
        guard let targetPeripheral else { return }
        centralManager.cancelPeripheralConnection(targetPeripheral)
    }

    /// Hallazgo real de code review (#64): sin esto en *todos* los caminos
    /// terminales (no solo el éxito y `disconnect()` manual), el timer de
    /// reintento seguía disparando cada 3s aunque ya se hubiera reportado un
    /// error al usuario - por ejemplo, si la víctima salió de
    /// `AYUDA_SOLICITADA` mientras el rescatista intentaba conectar
    /// (`didDiscoverServices` sin el servicio), el reintento reconectaba
    /// una y otra vez contra un host que ya no tiene el chat, en vez de
    /// asentarse en el error mostrado.
    private func stopRetrying() {
        retryTimer?.invalidate()
        retryTimer = nil
        centralManager.stopScan()
    }

    /// `nil` mientras el handshake no terminó (`ChatClientSession.encryptOwnMessage`
    /// todavía no tiene la clave simétrica) — la UI debe deshabilitar el
    /// envío hasta que `onConnected` dispare.
    func sendMessage(_ text: String) {
        guard let peripheral = targetPeripheral,
              let messageCharacteristic,
              let sealed = session.encryptOwnMessage(text, sentAt: UInt32(Date().timeIntervalSince1970))
        else { return }
        peripheral.writeValue(sealed, for: messageCharacteristic, type: .withResponse)
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        onDebugStatus?("centralManagerDidUpdateState=\(central.state.rawValue)")
        guard central.state == .poweredOn, let pendingPeripheralIdentifier else { return }
        self.pendingPeripheralIdentifier = nil
        connect(identifier: pendingPeripheralIdentifier)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        stopRetrying()
        onDebugStatus?("didConnect, descubriendo servicios")
        peripheral.discoverServices([ChatGattService.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        stopRetrying()
        onDebugStatus?("didFailToConnect error=\(String(describing: error))")
        onError?("No se pudo conectar al chat: \(error?.localizedDescription ?? "desconocido")")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        onDebugStatus?("didDisconnectPeripheral error=\(String(describing: error))")
        messageCharacteristic = nil
        guestPublicKeyCharacteristic = nil
        onDisconnected?()
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        onDebugStatus?("didDiscoverServices error=\(String(describing: error)) services=\(String(describing: peripheral.services))")
        guard let service = peripheral.services?.first(where: { $0.uuid == ChatGattService.serviceUUID }) else {
            stopRetrying()
            onError?("El caso elegido ya no tiene el chat disponible (dejó de pedir ayuda, o se desconectó).")
            centralManager.cancelPeripheralConnection(peripheral)
            return
        }
        peripheral.discoverCharacteristics(
            [ChatGattService.hostPublicKeyCharacteristicUUID, ChatGattService.guestPublicKeyCharacteristicUUID, ChatGattService.messageCharacteristicUUID],
            for: service
        )
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        onDebugStatus?("didDiscoverCharacteristicsFor error=\(String(describing: error)) chars=\(String(describing: service.characteristics))")
        guard let characteristics = service.characteristics,
              let hostKeyCharacteristic = characteristics.first(where: { $0.uuid == ChatGattService.hostPublicKeyCharacteristicUUID }),
              let guestKeyCharacteristic = characteristics.first(where: { $0.uuid == ChatGattService.guestPublicKeyCharacteristicUUID }),
              let messageCharacteristic = characteristics.first(where: { $0.uuid == ChatGattService.messageCharacteristicUUID })
        else {
            stopRetrying()
            onError?("El chat no expuso las características esperadas.")
            centralManager.cancelPeripheralConnection(peripheral)
            return
        }
        guestPublicKeyCharacteristic = guestKeyCharacteristic
        self.messageCharacteristic = messageCharacteristic
        // Suscribirse primero: es la única señal que `CBPeripheralManager`
        // recibe del lado host para saber que alguien se conectó (ver
        // `BleAdvertiser.didSubscribeTo`) — recién ahí el host genera su
        // clave efímera y la deja lista para leer.
        peripheral.setNotifyValue(true, for: messageCharacteristic)
        // El par X25519 propio no depende de la clave del host — se genera
        // ahora y se escribe de una vez, en paralelo con leer la del host.
        let ownPublicKeyData = session.startHandshake()
        peripheral.writeValue(ownPublicKeyData, for: guestKeyCharacteristic, type: .withResponse)
        peripheral.readValue(for: hostKeyCharacteristic)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        onDebugStatus?("didUpdateValueFor uuid=\(characteristic.uuid) error=\(String(describing: error)) bytes=\(characteristic.value?.count ?? -1)")
        guard error == nil, let data = characteristic.value else { return }
        switch characteristic.uuid {
        case ChatGattService.hostPublicKeyCharacteristicUUID:
            session.receivedHostPublicKey(data)
            onConnected?()
        case ChatGattService.messageCharacteristicUUID:
            session.receivedEncryptedPayload(data)
        default:
            break
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        if let error {
            onError?("No se pudo suscribir a mensajes nuevos: \(error.localizedDescription)")
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error {
            onError?("Falló un envío del chat: \(error.localizedDescription)")
        }
    }
}
