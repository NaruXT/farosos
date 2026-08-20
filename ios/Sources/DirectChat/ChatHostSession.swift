import CryptoKit
import Foundation

/// Orquestación pura del lado víctima/host del canal de chat directo
/// (#61) — el teléfono de la víctima aloja el canal, guarda el historial,
/// y lo comparte con cada rescatista que se conecta, uno a la vez. No
/// sabe nada de BLE/GATT — la capa de app (`ChatGattService`) traduce
/// eventos reales del `CBPeripheralManager` a estas llamadas, mismo
/// principio que el resto del proyecto separa lógica de "primitivo
/// nativo".
public final class ChatHostSession {
    /// Se dispara cada vez que el historial cambia (mensaje propio o del
    /// rescatista) — la capa de app lo persiste en `KeychainChatStore`,
    /// mismo patrón que `MeshStateRegistry.onStateUpdated`.
    public var onHistoryChanged: (([ChatMessage]) -> Void)?

    private let ownDeviceIdHash: Data
    private(set) public var history: [ChatMessage]
    private var activeConnection: Connection?

    private struct Connection {
        let privateKey: Curve25519.KeyAgreement.PrivateKey
        var symmetricKey: SymmetricKey?
    }

    public init(ownDeviceIdHash: Data, initialHistory: [ChatMessage] = []) {
        self.ownDeviceIdHash = ownDeviceIdHash
        self.history = initialHistory
    }

    /// `true` mientras ya hay una conexión activa — el AC de #61/#62 pide
    /// rechazar una segunda mientras esta siga abierta, decisión tomada
    /// explícitamente por costo de batería en la sesión de `/grilling`
    /// (no por límite técnico de BLE).
    public var hasActiveConnection: Bool { activeConnection != nil }

    /// Un rescatista se conectó — genera el par X25519 efímero de esta
    /// conexión (nunca se persiste, nunca se reusa entre conexiones) y
    /// devuelve la clave pública para que la capa de app la exponga en la
    /// característica de lectura. Devuelve `nil` si ya hay una conexión
    /// activa — el llamador debe rechazar/desconectar al segundo central.
    @discardableResult
    public func peerConnected() -> Data? {
        guard activeConnection == nil else { return nil }
        let (privateKey, publicKeyData) = EphemeralKeyAgreement.generateKeyPair()
        activeConnection = Connection(privateKey: privateKey, symmetricKey: nil)
        return publicKeyData
    }

    /// El rescatista conectado escribió su propia clave pública efímera —
    /// deriva la clave simétrica de esta sesión y devuelve el historial
    /// completo cifrado con ella, listo para notificar. `nil` si no hay
    /// conexión activa o la clave del peer es inválida.
    public func receivedPeerPublicKey(_ peerPublicKeyData: Data) -> Data? {
        guard var connection = activeConnection,
              let symmetricKey = try? EphemeralKeyAgreement.deriveSymmetricKey(
                  ownPrivateKey: connection.privateKey,
                  peerPublicKeyData: peerPublicKeyData
              )
        else { return nil }
        connection.symmetricKey = symmetricKey
        activeConnection = connection
        return sealHistory(with: symmetricKey)
    }

    /// El rescatista conectado escribió un mensaje nuevo cifrado — lo
    /// descifra, lo agrega al historial y lo persiste (`onHistoryChanged`).
    /// Un mensaje que no descifra con la clave de esta sesión se descarta
    /// en silencio (nunca crashea ante datos no confiables del peer).
    public func receivedEncryptedMessage(_ sealedData: Data) {
        guard let key = activeConnection?.symmetricKey,
              let plaintext = ChatCipher.open(sealedData, using: key),
              let text = String(data: plaintext, encoding: .utf8),
              let message = ChatMessageWireFormat.decode(text).first
        else { return }
        appendToHistory(message)
    }

    /// La víctima escribe un mensaje propio mientras hay una conexión
    /// activa — lo agrega al historial y devuelve el blob cifrado listo
    /// para notificar al rescatista conectado. `nil` si todavía no hay
    /// sesión con clave derivada (el rescatista se conectó pero no mandó
    /// su clave pública todavía). Empaquetado como lista de un elemento
    /// (no un `ChatMessage` suelto) a propósito: así el lado cliente
    /// (`ChatClientSession`) descifra siempre `[ChatMessage]` sin importar
    /// si el payload es el historial completo inicial o un mensaje nuevo —
    /// un solo camino de decodificación, no dos formatos de wire distintos.
    public func sendOwnMessage(_ text: String, sentAt: UInt32) -> Data? {
        guard let key = activeConnection?.symmetricKey else { return nil }
        let message = ChatMessage(fromVictim: true, text: text, sentAtEpochSeconds: sentAt)
        appendToHistory(message)
        let plaintext = Data(ChatMessageWireFormat.encode([message]).utf8)
        return try? ChatCipher.seal(plaintext, using: key)
    }

    /// El rescatista se desconectó (o la capa de app decide cerrar la
    /// conexión) — libera el slot para que otro pueda conectarse después.
    /// El historial persiste (vive en `self.history`, no en la conexión).
    public func peerDisconnected() {
        activeConnection = nil
    }

    /// Una notificación GATT (a diferencia de una escritura) nunca se
    /// fragmenta a nivel de protocolo - tiene que caber entera en un solo
    /// paquete ATT. Con el MTU de 517 bytes que este proyecto negocia, el
    /// máximo utilizable es 514; este valor se queda por debajo con margen
    /// de sobra para el caso más chico que ambas plataformas negocian en la
    /// práctica. Hallazgo de campo (#64): el historial acumulado de una
    /// conversación real supera esto fácilmente, y una notificación más
    /// grande que el paquete se trunca en silencio - el tag de AES-GCM
    /// nunca calza y el receptor descarta todo el historial, no solo lo
    /// que sobraba.
    private static let maxSealedHistoryBytes = 480

    /// Sella tantos de los mensajes más recientes como quepan en un solo
    /// paquete de notificación - descarta los más viejos, uno por uno,
    /// hasta que el blob cifrado entre. `nil` solo si ni el mensaje más
    /// reciente por sí solo entra (mensaje individual demasiado largo).
    private func sealHistory(with key: SymmetricKey) -> Data? {
        var candidateHistory = history
        while true {
            let plaintext = Data(ChatMessageWireFormat.encode(candidateHistory).utf8)
            if let sealed = try? ChatCipher.seal(plaintext, using: key), sealed.count <= Self.maxSealedHistoryBytes {
                return sealed
            }
            guard !candidateHistory.isEmpty else { return nil }
            candidateHistory.removeFirst()
        }
    }

    private func appendToHistory(_ message: ChatMessage) {
        history.append(message)
        onHistoryChanged?(history)
    }
}
