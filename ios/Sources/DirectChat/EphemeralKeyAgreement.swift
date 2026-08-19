import CryptoKit
import Foundation

/// ECDH efímero por conexión (#61) — decisión explícita de la sesión de
/// `/grilling`, distinta de `CaseBAuthentication.deriveKShared`: acá ambos
/// lados generan un par X25519 nuevo directamente (soportado nativamente
/// por `CryptoKit`, sin necesitar la conversión Ed25519→X25519 que Caso B
/// sí requiere y que no existe del lado del cliente para claves públicas
/// ajenas). No ata la clave a la identidad Ed25519 permanente — protege
/// contra un tercero no conectado escuchando el aire, no contra un
/// participante que ya demostró presencia física conectándose (historia 11
/// de #61).
public enum EphemeralKeyAgreement {
    /// Par de claves X25519 nuevo, generado una vez por conexión. Nunca se
    /// persiste — vive solo mientras dura la conexión GATT.
    public static func generateKeyPair() -> (privateKey: Curve25519.KeyAgreement.PrivateKey, publicKeyData: Data) {
        let privateKey = Curve25519.KeyAgreement.PrivateKey()
        return (privateKey, privateKey.publicKey.rawRepresentation)
    }

    /// Deriva la clave simétrica de la sesión a partir del secreto ECDH
    /// crudo — HKDF-SHA256 sin sal ni info (ninguna hace falta: la clave ya
    /// es única por conexión porque ambos pares de X25519 son efímeros).
    public static func deriveSymmetricKey(
        ownPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        peerPublicKeyData: Data
    ) throws -> SymmetricKey {
        let peerPublicKey = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerPublicKeyData)
        let sharedSecret = try ownPrivateKey.sharedSecretFromKeyAgreement(with: peerPublicKey)
        return sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: Data("farosos-direct-chat".utf8),
            outputByteCount: 32
        )
    }

    /// Expone el secreto ECDH crudo (antes de HKDF) — solo para verificar la
    /// implementación contra el vector de prueba estándar de X25519 (RFC
    /// 7748, sección 5.2), igual que `CaseBAuthentication` se verificó
    /// contra `spec/test-vectors.json`. Nunca se usa en el protocolo real
    /// (que siempre deriva la clave simétrica vía HKDF).
    static func rawSharedSecret(
        ownPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        peerPublicKeyData: Data
    ) throws -> Data {
        let peerPublicKey = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerPublicKeyData)
        let sharedSecret = try ownPrivateKey.sharedSecretFromKeyAgreement(with: peerPublicKey)
        return sharedSecret.withUnsafeBytes { Data($0) }
    }
}
