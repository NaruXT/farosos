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
    /// Diagnóstico temporal de campo (#64) - se muestra en pantalla porque
    /// la consola por USB (`devicectl --console`) no capturó el `print()`
    /// de `ChatCentralConnection` en este dispositivo.
    @Published private(set) var debugStatus = "init"

    private let connection = ChatCentralConnection()

    init(peripheral: CBPeripheral, deviceIdHash: Data) {
        connection.onMessagesReceived = { [weak self] newMessages in
            guard let self else { return }
            self.messages.append(contentsOf: newMessages)
        }
        connection.onConnected = { [weak self] in self?.isConnected = true }
        connection.onDisconnected = { [weak self] in self?.isConnected = false }
        connection.onError = { [weak self] message in self?.errorMessage = message }
        connection.onDebugStatus = { [weak self] status in self?.debugStatus = status }
        debugStatus = "peripheral=\(peripheral.identifier) state=\(peripheral.state.rawValue)"
        connection.connect(to: peripheral, deviceIdHash: deviceIdHash)
    }

    /// A diferencia de `ChatHostSession.sendOwnMessage` (que agrega al
    /// historial internamente), `ChatClientSession.encryptOwnMessage` no
    /// mantiene historial propio — acá se agrega localmente antes de
    /// enviar, para que la propia UI lo muestre de inmediato sin esperar
    /// ningún eco del host.
    func send(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        connection.sendMessage(trimmed)
        messages.append(ChatMessage(fromVictim: false, text: trimmed, sentAtEpochSeconds: UInt32(Date().timeIntervalSince1970)))
    }

    /// Este view model es siempre el lado rescatista - "propio" es
    /// simplemente "no de la víctima".
    func isOwnMessage(_ message: ChatMessage) -> Bool {
        !message.fromVictim
    }

    func stop() {
        connection.disconnect()
    }
}
