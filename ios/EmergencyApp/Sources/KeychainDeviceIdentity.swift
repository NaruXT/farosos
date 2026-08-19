import CryptoKit
import DeviceIdentity
import Foundation

/// Genera y persiste un keypair Ed25519 en Keychain al instalar (decisión de
/// arquitectura 17, `spec/packet-format.md` — reemplaza el UUID de
/// instalación anterior) y deriva el `deviceIdHash` de 6 bytes
/// (`DeviceIdentityHash.fromPublicKey`, testeado contra los vectores de #39)
/// que viaja en cada `BeaconPacket` emitido por este nodo. La clave privada
/// nunca sale del dispositivo. Vive en la capa de app, no en el paquete SPM
/// testeado — Keychain requiere el entorno real de la app, igual que
/// `RealScheduler`.
enum KeychainDeviceIdentity {
    private static let account = "ed25519PrivateKey"
    private static let proofOfWorkAccount = "proofOfWorkNonce"

    static func deviceIdHash() -> Data {
        DeviceIdentityHash.fromPublicKey(publicKeyEd25519())
    }

    /// Clave pública Ed25519 cruda (32 bytes) — la sube el registro opt-in
    /// (#46) para que el backend pueda derivar `K_shared` por ECDH en Caso B
    /// (#38/#48). Nunca la clave privada, que no sale del dispositivo.
    static func publicKeyEd25519() -> Data {
        privateKey().publicKey.rawRepresentation
    }

    /// Mitigación Sybil de Caso A (#50) — calcula el sello de Prueba de
    /// Trabajo sobre `deviceIdHash` una única vez y lo persiste, igual que
    /// la identidad Ed25519. Si ya hay un sello guardado y sigue siendo
    /// válido (mismo `deviceIdHash`, cumple `ProofOfWork.difficultyBits`
    /// actual) lo reutiliza sin recalcular; si no, lo recalcula — cubre
    /// tanto la primera vez como un cambio futuro de dificultad o de
    /// identidad (reinstalación). Recibe `deviceIdHash` en vez de volver a
    /// derivarlo de Keychain, porque quien llama ya lo tiene.
    static func proofOfWorkSeal(deviceIdHash: Data) -> Data {
        if let stored = KeychainStore.read(account: proofOfWorkAccount),
           let nonce = Data(base64Encoded: stored),
           ProofOfWork.isValid(deviceIdHash: deviceIdHash, nonce: nonce) {
            return nonce
        }
        let nonce = ProofOfWork.solve(deviceIdHash: deviceIdHash)
        KeychainStore.write(nonce.base64EncodedString(), account: proofOfWorkAccount)
        return nonce
    }

    private static func privateKey() -> Curve25519.Signing.PrivateKey {
        if let stored = KeychainStore.read(account: account),
           let rawKey = Data(base64Encoded: stored),
           let existing = try? Curve25519.Signing.PrivateKey(rawRepresentation: rawKey) {
            return existing
        }
        let generated = Curve25519.Signing.PrivateKey()
        KeychainStore.write(generated.rawRepresentation.base64EncodedString(), account: account)
        return generated
    }
}
