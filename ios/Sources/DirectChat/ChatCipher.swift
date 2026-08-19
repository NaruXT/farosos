import CryptoKit
import Foundation

/// Cifrado en tránsito de los mensajes del canal de chat directo (#61) —
/// AEAD simétrico con la clave de sesión derivada por
/// `EphemeralKeyAgreement`. Se eligió `ChaChaPoly` sobre `AES.GCM` porque
/// `.combined` empaqueta nonce+texto cifrado+tag en un solo `Data`, listo
/// para viajar como el valor de una característica GATT sin manejo de
/// nonce aparte del lado de la app.
public enum ChatCipher {
    public static func seal(_ plaintext: Data, using key: SymmetricKey) throws -> Data {
        try ChaChaPoly.seal(plaintext, using: key).combined
    }

    /// `nil` si el sello no abre (clave equivocada o datos corruptos/ajenos)
    /// — nunca lanza hacia arriba, el llamador decide qué hacer con un
    /// mensaje ilegible (mismo principio de "nunca crashear" del resto del
    /// proyecto ante datos no confiables de la malla).
    public static func open(_ sealedData: Data, using key: SymmetricKey) -> Data? {
        guard let box = try? ChaChaPoly.SealedBox(combined: sealedData) else { return nil }
        return try? ChaChaPoly.open(box, using: key)
    }
}
