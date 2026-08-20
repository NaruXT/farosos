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
    /// crudo - SHA-256 directo sobre el secreto, sin HKDF. Reescrito
    /// durante la verificación de campo real de #64: la primera versión
    /// usaba HKDF-SHA256 (con `sharedInfo` propio), incompatible con
    /// Android (`ChatCrypto.deriveSessionKey`, #63), que deriva con
    /// SHA-256 plano - dos claves de sesión distintas a partir del mismo
    /// secreto ECDH, ningún mensaje cifrado por un lado abría del otro. Se
    /// adoptó el esquema de Android como canónico (mismo principio que la
    /// reconciliación de características GATT): un solo paso de digest
    /// alcanza porque el secreto ya tiene alta entropía y se usa una sola
    /// vez por conexión, sin contexto adicional que mezclar.
    public static func deriveSymmetricKey(
        ownPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        peerPublicKeyData: Data
    ) throws -> SymmetricKey {
        let rawSecret = try rawSharedSecret(ownPrivateKey: ownPrivateKey, peerPublicKeyData: peerPublicKeyData)
        return SymmetricKey(data: SHA256.hash(data: rawSecret))
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
