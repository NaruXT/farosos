import CryptoKit
import Foundation

/// Orquestación pura del lado rescatista/cliente del canal de chat
/// directo (#61) — contraparte de `ChatHostSession`. No sabe nada de
/// BLE/GATT, la capa de app (`ChatCentralConnection`) traduce eventos
/// reales del `CBCentralManager`/`CBPeripheral` a estas llamadas.
public final class ChatClientSession {
    /// Se dispara cada vez que llegan mensajes nuevos (el historial inicial
    /// completo, o un mensaje nuevo de la víctima) — la capa de app
    /// actualiza la UI con esto.
    public var onMessagesReceived: (([ChatMessage]) -> Void)?

    private(set) public var privateKey: Curve25519.KeyAgreement.PrivateKey?
    private var symmetricKey: SymmetricKey?

    public init() {}

    /// Al conectar, genera el par X25519 efímero propio de esta conexión —
    /// la capa de app escribe la clave pública devuelta en la característica
    /// correspondiente del host.
    public func startHandshake() -> Data {
        let (key, publicKeyData) = EphemeralKeyAgreement.generateKeyPair()
        privateKey = key
        return publicKeyData
    }

    /// Se leyó la clave pública efímera de la víctima — deriva la clave
    /// simétrica de la sesión. Después de esto ya se puede mandar/recibir
    /// mensajes.
    public func receivedHostPublicKey(_ hostPublicKeyData: Data) {
        guard let privateKey else { return }
        symmetricKey = try? EphemeralKeyAgreement.deriveSymmetricKey(
            ownPrivateKey: privateKey,
            peerPublicKeyData: hostPublicKeyData
        )
    }

    /// Llega una notificación de la característica de mensajes — siempre
    /// una lista (historial completo la primera vez, un mensaje nuevo en
    /// las siguientes, mismo formato de wire en ambos casos, ver
    /// `ChatHostSession.sendOwnMessage`). Se descarta en silencio si no
    /// descifra con la clave de esta sesión.
    public func receivedEncryptedPayload(_ sealedData: Data) {
        guard let key = symmetricKey,
              let plaintext = ChatCipher.open(sealedData, using: key),
              let messages = try? JSONDecoder().decode([ChatMessage].self, from: plaintext)
        else { return }
        onMessagesReceived?(messages)
    }

    /// El rescatista escribe un mensaje propio — devuelve el blob cifrado
    /// listo para escribir en la característica de mensajes del host. `nil`
    /// si todavía no se derivó la clave simétrica (el handshake no
    /// terminó).
    public func encryptOwnMessage(_ text: String, ownDeviceIdHash: Data, sentAt: UInt32) -> Data? {
        guard let key = symmetricKey else { return nil }
        let message = ChatMessage(senderDeviceIdHash: ownDeviceIdHash, text: text, sentAt: sentAt)
        guard let plaintext = try? JSONEncoder().encode([message]) else { return nil }
        return try? ChatCipher.seal(plaintext, using: key)
    }
}
