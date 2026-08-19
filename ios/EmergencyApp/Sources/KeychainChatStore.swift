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
        guard let json = KeychainStore.read(account: account), let data = json.data(using: .utf8) else { return [] }
        return (try? JSONDecoder().decode([ChatMessage].self, from: data)) ?? []
    }

    static func save(_ history: [ChatMessage]) {
        guard let data = try? JSONEncoder().encode(history), let json = String(data: data, encoding: .utf8) else { return }
        KeychainStore.write(json, account: account)
    }
}
