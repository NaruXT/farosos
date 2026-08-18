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

    static func deviceIdHash() -> Data {
        DeviceIdentityHash.fromPublicKey(privateKey().publicKey.rawRepresentation)
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
