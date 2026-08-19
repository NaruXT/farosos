import CoreBluetooth
import DirectChat
import Foundation

/// Pantalla de chat del lado rescatista (#61/#62) — conecta a un
/// `CBPeripheral` específico ya conocido (elegido desde "Casos") y
/// mantiene la conversación mientras la vista está abierta.
@MainActor
final class ChatViewModel: ObservableObject {
    @Published private(set) var messages: [ChatMessage] = []
    @Published private(set) var isConnected = false
    @Published private(set) var errorMessage: String?

    private let ownDeviceIdHash: Data
    private let connection = ChatCentralConnection()

    init(ownDeviceIdHash: Data, peripheral: CBPeripheral) {
        self.ownDeviceIdHash = ownDeviceIdHash
        connection.onMessagesReceived = { [weak self] newMessages in
            guard let self else { return }
            self.messages.append(contentsOf: newMessages)
        }
        connection.onConnected = { [weak self] in self?.isConnected = true }
        connection.onDisconnected = { [weak self] in self?.isConnected = false }
        connection.onError = { [weak self] message in self?.errorMessage = message }
        connection.connect(to: peripheral)
    }

    /// A diferencia de `ChatHostSession.sendOwnMessage` (que agrega al
    /// historial internamente), `ChatClientSession.encryptOwnMessage` no
    /// mantiene historial propio — acá se agrega localmente antes de
    /// enviar, para que la propia UI lo muestre de inmediato sin esperar
    /// ningún eco del host.
    func send(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        connection.sendMessage(trimmed, ownDeviceIdHash: ownDeviceIdHash)
        messages.append(ChatMessage(senderDeviceIdHash: ownDeviceIdHash, text: trimmed, sentAt: UInt32(Date().timeIntervalSince1970)))
    }

    func isOwnMessage(_ message: ChatMessage) -> Bool {
        message.senderDeviceIdHash == ownDeviceIdHash
    }

    func stop() {
        connection.disconnect()
    }
}
