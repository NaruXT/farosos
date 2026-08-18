import CryptoKit
import Foundation

/// Autocertificado de Caso A (`spec/packet-format.md`, sección
/// `FRAGMENTO_FIRMA`, decisión 18): el dispositivo firma su propia clave
/// pública Ed25519 — prueba posesión de la identidad, no autentica el
/// contenido de ningún beacon individual (límite aceptado, ver spec).
/// Vectores de prueba en `spec/test-vectors.json`, clave
/// `fragmento_firma.identity`.
///
/// `CryptoKit.Curve25519.Signing` firma con un nonce aleatorio por firma
/// ("hedged", mitigación de fault-injection) — a diferencia de la firma
/// EdDSA determinística de RFC 8032 (usada por `@noble/curves` para generar
/// `signature_hex` en los vectores). Dos firmas de CryptoKit sobre el mismo
/// mensaje con la misma clave NO son iguales byte a byte entre sí, ni
/// contra el vector — ambas son igual de válidas. Por eso los tests de esta
/// clase verifican `verify(sign(...))`, nunca una igualdad byte a byte
/// contra `signature_hex`.
public enum CaseASignature {
    /// Tamaños fijos de Ed25519 — fuente única para quien necesite partir o
    /// validar un payload `pubkey || firma` (`SignatureFragmenter`).
    public static let publicKeyLength = 32
    public static let signatureLength = 64

    /// `firma = Ed25519_Sign(privkey, pubkey)`.
    public static func sign(privateKey: Curve25519.Signing.PrivateKey) -> Data {
        let publicKey = privateKey.publicKey.rawRepresentation
        // Ed25519 firma cualquier mensaje sin condiciones de error reales
        // para una clave válida — mismo patrón de `try!` que
        // `CaseBAuthentication` para operaciones garantizadas por CryptoKit.
        return try! privateKey.signature(for: publicKey)
    }

    /// Verifica que `signature` sea un autocertificado válido de `publicKey`
    /// (es decir, `signature` firma `publicKey` bajo esa misma clave).
    public static func verify(publicKey: Data, signature: Data) -> Bool {
        guard let key = try? Curve25519.Signing.PublicKey(rawRepresentation: publicKey) else { return false }
        return key.isValidSignature(signature, for: publicKey)
    }
}
