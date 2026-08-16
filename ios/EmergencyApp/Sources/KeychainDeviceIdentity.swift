import CryptoKit
import Foundation

/// Persiste el UUID de instalación en Keychain (decisión de arquitectura 6,
/// `spec/packet-format.md`) y deriva el `deviceIdHash` de 6 bytes
/// (`SHA-256(UUID)` truncado) que viaja en cada `BeaconPacket` emitido por
/// este nodo. Vive en la capa de app, no en el paquete SPM testeado —
/// Keychain requiere el entorno real de la app, igual que `RealScheduler`.
enum KeychainDeviceIdentity {
    private static let account = "installationId"

    static func deviceIdHash() -> Data {
        let digest = SHA256.hash(data: Data(installationUUIDString().utf8))
        return Data(digest.prefix(6))
    }

    private static func installationUUIDString() -> String {
        if let existing = KeychainStore.read(account: account) { return existing }
        let generated = UUID().uuidString
        KeychainStore.write(generated, account: account)
        return generated
    }
}
