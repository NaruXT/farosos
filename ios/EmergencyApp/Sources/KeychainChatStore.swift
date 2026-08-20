import DirectChat
import Foundation

/// Persiste el historial del canal de chat directo (#61/#62) en texto
/// plano en Keychain — mismo mecanismo que protege `ParticipantStore`/
/// `KeychainResolutionStore`. El cifrado del chat protege el aire, no el
/// teléfono (decisión explícita de la sesión de `/grilling`): acá no hace
/// falta cifrar de nuevo, el propio Keychain ya lo hace.
enum KeychainChatStore {
    private static let account = "directChatHistory"

    static func history() -> [ChatMessage] {
        guard let raw = KeychainStore.read(account: account) else { return [] }
        return ChatMessageWireFormat.decode(raw)
    }

    static func save(_ history: [ChatMessage]) {
        KeychainStore.write(ChatMessageWireFormat.encode(history), account: account)
    }
}
