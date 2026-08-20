import CryptoKit
import Foundation

/// Cifrado en tránsito de los mensajes del canal de chat directo (#61) —
/// AEAD simétrico con la clave de sesión derivada por
/// `EphemeralKeyAgreement`. `.combined` empaqueta nonce+texto cifrado+tag
/// en un solo `Data`, listo para viajar como el valor de una característica
/// GATT sin manejo de nonce aparte del lado de la app.
///
/// Reescrito a `AES.GCM` durante la verificación de campo real de #64: la
/// primera versión usaba `ChaChaPoly`, incompatible con Android
/// (`ChatCrypto`, #63), que usa AES/GCM nativo de `javax.crypto` - dos
/// algoritmos de cifrado distintos, ningún mensaje interoperaba entre
/// plataformas. Se adoptó AES-GCM como canónico (no al revés) porque
/// `ChaCha20-Poly1305` en `javax.crypto` de Android recién está garantizado
/// desde una API más nueva que el `minSdk` de este proyecto (26), mientras
/// que AES-GCM es nativo y confiable desde API 1 en Android y desde iOS 13
/// en CryptoKit - el mismo criterio que ya decidió BouncyCastle vs. nativo
/// para Ed25519/X25519 (#41). El formato de wire (`nonce/IV(12) ||
/// ciphertext+tag`) ya coincidía entre ambas implementaciones por
/// casualidad - solo cambia el algoritmo.
public enum ChatCipher {
    public static func seal(_ plaintext: Data, using key: SymmetricKey) throws -> Data {
        try AES.GCM.seal(plaintext, using: key).combined!
    }

    /// `nil` si el sello no abre (clave equivocada o datos corruptos/ajenos)
    /// — nunca lanza hacia arriba, el llamador decide qué hacer con un
    /// mensaje ilegible (mismo principio de "nunca crashear" del resto del
    /// proyecto ante datos no confiables de la malla).
    public static func open(_ sealedData: Data, using key: SymmetricKey) -> Data? {
        guard let box = try? AES.GCM.SealedBox(combined: sealedData) else { return nil }
        return try? AES.GCM.open(box, using: key)
    }
}
