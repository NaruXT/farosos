import CryptoKit
import Foundation

/// `device_id_hash = SHA-256(clave pública Ed25519)[:6 bytes]` — ver
/// `spec/packet-format.md` decisión 17. Pura y testeable: quien genera y
/// persiste el keypair real (`KeychainDeviceIdentity`, capa de app) delega
/// aquí el cómputo del hash.
public enum DeviceIdentityHash {
    public static func fromPublicKey(_ publicKey: Data) -> Data {
        Data(SHA256.hash(data: publicKey).prefix(6))
    }
}
