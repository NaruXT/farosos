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

    override init() {
        super.init()
        session.onMessagesReceived = { [weak self] messages in self?.onMessagesReceived?(messages) }
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
    func connect(to peripheral: CBPeripheral) {
        let identifier = peripheral.identifier
        guard centralManager.state == .poweredOn else {
            onDebugStatus?("esperando poweredOn (state=\(centralManager.state.rawValue))")
            pendingPeripheralIdentifier = identifier
            return
        }
        connect(identifier: identifier)
    }

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
        startPolling()
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
        guard let targetPeripheral else { return }
        centralManager.cancelPeripheralConnection(targetPeripheral)
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
        onDebugStatus?("didConnect, descubriendo servicios")
        peripheral.discoverServices([ChatGattService.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
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
