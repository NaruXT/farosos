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

    private lazy var centralManager = CBCentralManager(delegate: self, queue: nil)
    private let session = ChatClientSession()
    private var targetPeripheral: CBPeripheral?
    private var messageCharacteristic: CBCharacteristic?
    private var guestPublicKeyCharacteristic: CBCharacteristic?

    /// Se guarda hasta que el manager llegue a `poweredOn` — puede que
    /// `connect(to:)` se llame antes de que el radio esté listo.
    private var pendingPeripheral: CBPeripheral?

    override init() {
        super.init()
        session.onMessagesReceived = { [weak self] messages in self?.onMessagesReceived?(messages) }
    }

    func connect(to peripheral: CBPeripheral) {
        targetPeripheral = peripheral
        peripheral.delegate = self
        guard centralManager.state == .poweredOn else {
            pendingPeripheral = peripheral
            return
        }
        centralManager.connect(peripheral)
    }

    func disconnect() {
        guard let targetPeripheral else { return }
        centralManager.cancelPeripheralConnection(targetPeripheral)
    }

    /// `nil` mientras el handshake no terminó (`ChatClientSession.encryptOwnMessage`
    /// todavía no tiene la clave simétrica) — la UI debe deshabilitar el
    /// envío hasta que `onConnected` dispare.
    func sendMessage(_ text: String, ownDeviceIdHash: Data) {
        guard let peripheral = targetPeripheral,
              let messageCharacteristic,
              let sealed = session.encryptOwnMessage(text, ownDeviceIdHash: ownDeviceIdHash, sentAt: UInt32(Date().timeIntervalSince1970))
        else { return }
        peripheral.writeValue(sealed, for: messageCharacteristic, type: .withResponse)
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard central.state == .poweredOn, let pendingPeripheral else { return }
        self.pendingPeripheral = nil
        central.connect(pendingPeripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([ChatGattService.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        onError?("No se pudo conectar al chat: \(error?.localizedDescription ?? "desconocido")")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        messageCharacteristic = nil
        guestPublicKeyCharacteristic = nil
        onDisconnected?()
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
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
