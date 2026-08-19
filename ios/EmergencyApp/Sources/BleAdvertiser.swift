import BeaconRadio
import CoreBluetooth
import Foundation

/// Envoltorio de `CBPeripheralManager` para publicar el beacon actual del
/// nodo. iOS no puede anunciar Manufacturer Specific Data como periférico
/// (ver `BeaconGattService`), así que el advertisement solo señaliza "soy
/// un nodo Farosos" (Service UUID + Local Name) y el `BeaconPacket`
/// completo viaja como el valor de una característica GATT de solo
/// lectura.
final class BleAdvertiser: NSObject, CBPeripheralManagerDelegate {
    var onError: ((String) -> Void)?

    // MARK: - Chat directo (#61/#62)
    //
    // Mismo `CBPeripheralManager` que el beacon, no uno aparte — evita que
    // dos instancias compitan por el mismo radio Bluetooth. El servicio de
    // chat se agrega/retira dinámicamente según `setChatServiceEnabled`
    // (activo solo mientras el propio estado pide ayuda, decisión de
    // batería de la sesión de `/grilling`) — no cambia la lista de
    // servicios del advertisement en sí: un rescatista que ya conoce este
    // `CBPeripheral` (por haber recibido su beacon antes) se conecta
    // directo y descubre lo que haya agregado en ese momento, sin depender
    // de que el chat aparezca anunciado.
    var onChatGuestConnected: (() -> Void)?
    var onChatGuestPublicKeyWritten: ((Data) -> Void)?
    var onChatMessageWritten: ((Data) -> Void)?
    var onChatGuestDisconnected: (() -> Void)?

    private lazy var peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
    private var currentData: Data?
    private var isServiceAdded = false

    private var chatEnabled = false
    private var isChatServiceAdded = false
    /// Retenido para poder pasarlo a `peripheralManager.remove(_:)` — CoreBluetooth
    /// identifica el servicio a quitar por identidad de objeto, no solo por
    /// UUID, así que reconstruir un `CBMutableService` nuevo con el mismo
    /// UUID para removerlo no funcionaría.
    private var chatService: CBMutableService?
    private var hostPublicKeyCharacteristic: CBMutableCharacteristic?
    private var messageCharacteristic: CBMutableCharacteristic?
    private var hostPublicKeyData: Data?
    private var subscribedChatCentral: CBCentral?

    /// Reemplaza el payload que este nodo publica. El valor de la
    /// característica se resuelve dinámicamente en `didReceiveRead` a
    /// partir de `currentData`, así que no hace falta reiniciar el
    /// advertising cuando el paquete cambia — solo la primera vez, para
    /// registrar el servicio GATT.
    func updateAdvertisedData(_ data: Data) {
        currentData = data
        guard peripheralManager.state == .poweredOn, !isServiceAdded else { return }
        setUpService()
    }

    /// Activa/desactiva el servicio de chat — se llama desde
    /// `EmergencyViewModel` cada vez que cambia `PersonState.isRequestingHelp`.
    /// Al desactivarse, corta cualquier conexión de chat en curso (nadie
    /// puede seguir chateando con alguien que ya no está pidiendo ayuda).
    func setChatServiceEnabled(_ enabled: Bool) {
        chatEnabled = enabled
        guard peripheralManager.state == .poweredOn else { return }
        if enabled {
            addChatServiceIfNeeded()
        } else {
            removeChatServiceIfNeeded()
        }
    }

    /// Deja lista la clave pública X25519 efímera de esta conexión para que
    /// el rescatista la lea — se llama justo después de que `ChatHostSession`
    /// acepta la conexión (`peerConnected()`).
    func setChatHostPublicKey(_ data: Data) {
        hostPublicKeyData = data
    }

    /// Notifica un blob cifrado (historial inicial o mensaje nuevo) al
    /// rescatista actualmente suscrito. No hace nada si nadie está
    /// suscrito — la capa de `ChatHostSession` ya garantiza que esto solo
    /// se llama con una conexión activa.
    func notifyChatMessage(_ data: Data) {
        guard let messageCharacteristic, let subscribedChatCentral else { return }
        peripheralManager.updateValue(data, for: messageCharacteristic, onSubscribedCentrals: [subscribedChatCentral])
    }

    private func addChatServiceIfNeeded() {
        guard !isChatServiceAdded else { return }
        let hostKey = CBMutableCharacteristic(
            type: ChatGattService.hostPublicKeyCharacteristicUUID,
            properties: [.read],
            value: nil,
            permissions: [.readable]
        )
        let guestKey = CBMutableCharacteristic(
            type: ChatGattService.guestPublicKeyCharacteristicUUID,
            properties: [.write],
            value: nil,
            permissions: [.writeable]
        )
        let message = CBMutableCharacteristic(
            type: ChatGattService.messageCharacteristicUUID,
            properties: [.write, .notify],
            value: nil,
            permissions: [.writeable]
        )
        hostPublicKeyCharacteristic = hostKey
        messageCharacteristic = message
        let service = CBMutableService(type: ChatGattService.serviceUUID, primary: true)
        service.characteristics = [hostKey, guestKey, message]
        chatService = service
        peripheralManager.add(service)
        isChatServiceAdded = true
    }

    private func removeChatServiceIfNeeded() {
        guard isChatServiceAdded, let chatService else { return }
        peripheralManager.remove(chatService)
        self.chatService = nil
        isChatServiceAdded = false
        hostPublicKeyCharacteristic = nil
        messageCharacteristic = nil
        hostPublicKeyData = nil
        if subscribedChatCentral != nil {
            subscribedChatCentral = nil
            onChatGuestDisconnected?()
        }
    }

    private func setUpService() {
        let characteristic = CBMutableCharacteristic(
            type: BeaconGattService.characteristicUUID,
            properties: [.read],
            value: nil,
            permissions: [.readable]
        )
        let service = CBMutableService(type: BeaconGattService.serviceUUID, primary: true)
        service.characteristics = [characteristic]
        peripheralManager.add(service)
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard peripheral.state == .poweredOn else {
            // Un power-cycle real de Bluetooth invalida los servicios GATT ya
            // agregados — hay que re-agregarlo cuando vuelva a poweredOn, no
            // solo reanudar el advertising.
            isServiceAdded = false
            isChatServiceAdded = false
            chatService = nil
            return
        }
        if isServiceAdded {
            startAdvertising()
        } else if currentData != nil {
            setUpService()
        }
        if chatEnabled {
            addChatServiceIfNeeded()
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error {
            onError?("Error al agregar el servicio GATT: \(error.localizedDescription)")
            return
        }
        if service.uuid == BeaconGattService.serviceUUID {
            isServiceAdded = true
            startAdvertising()
        }
    }

    private func startAdvertising() {
        peripheralManager.stopAdvertising()
        peripheralManager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [BeaconGattService.serviceUUID],
            CBAdvertisementDataLocalNameKey: "Farosos"
        ])
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        guard let error else { return }
        onError?("Error al iniciar advertising: \(error.localizedDescription)")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        switch request.characteristic.uuid {
        case BeaconGattService.characteristicUUID:
            respondToBeaconRead(request)
        case ChatGattService.hostPublicKeyCharacteristicUUID:
            respondToChatHostPublicKeyRead(request)
        default:
            peripheralManager.respond(to: request, withResult: .attributeNotFound)
        }
    }

    private func respondToBeaconRead(_ request: CBATTRequest) {
        guard let data = currentData, request.offset <= data.count else {
            peripheralManager.respond(to: request, withResult: .invalidOffset)
            return
        }
        request.value = data.subdata(in: request.offset..<data.count)
        peripheralManager.respond(to: request, withResult: .success)
    }

    private func respondToChatHostPublicKeyRead(_ request: CBATTRequest) {
        guard let data = hostPublicKeyData, request.offset <= data.count else {
            peripheralManager.respond(to: request, withResult: .invalidOffset)
            return
        }
        request.value = data.subdata(in: request.offset..<data.count)
        peripheralManager.respond(to: request, withResult: .success)
    }

    /// Solo procesa escrituras del central ya suscripto/activo (ver
    /// `didSubscribeTo`) — evita que una segunda conexión que nunca llegó a
    /// ser la activa pueda inyectar datos en la sesión de otro.
    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            if request.central == subscribedChatCentral, let value = request.value {
                switch request.characteristic.uuid {
                case ChatGattService.guestPublicKeyCharacteristicUUID:
                    onChatGuestPublicKeyWritten?(value)
                case ChatGattService.messageCharacteristicUUID:
                    onChatMessageWritten?(value)
                default:
                    break
                }
            }
            peripheralManager.respond(to: request, withResult: .success)
        }
    }

    /// El rescatista se suscribió a notificaciones — es el punto en que
    /// realmente se considera "conectado" para el chat (`ChatHostSession`
    /// necesita un central identificado para poder notificarle). `CBPeripheralManager`
    /// no expone una forma de rechazar/cortar la suscripción de un central
    /// específico (no hay equivalente a `cancelPeripheralConnection` del
    /// lado periférico) — la segunda suscripción mientras ya hay una activa
    /// simplemente nunca se registra como `subscribedChatCentral` ni recibe
    /// notificaciones, quedando efectivamente ignorada. `onChatGuestConnected`
    /// solo se dispara para la primera.
    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        guard characteristic.uuid == ChatGattService.messageCharacteristicUUID, subscribedChatCentral == nil else { return }
        subscribedChatCentral = central
        onChatGuestConnected?()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard characteristic.uuid == ChatGattService.messageCharacteristicUUID, central == subscribedChatCentral else { return }
        subscribedChatCentral = nil
        onChatGuestDisconnected?()
    }
}
