import CryptoKit
import Foundation
import Security

/// Persiste el UUID de instalación en Keychain (decisión de arquitectura 6,
/// `spec/packet-format.md`) y deriva el `deviceIdHash` de 6 bytes
/// (`SHA-256(UUID)` truncado) que viaja en cada `BeaconPacket` emitido por
/// este nodo. Vive en la capa de app, no en el paquete SPM testeado —
/// Keychain requiere el entorno real de la app, igual que `RealScheduler`.
enum KeychainDeviceIdentity {
    private static let account = "installationId"
    private static let service = "com.farosos.EmergencyApp"

    static func deviceIdHash() -> Data {
        let digest = SHA256.hash(data: Data(installationUUIDString().utf8))
        return Data(digest.prefix(6))
    }

    private static func installationUUIDString() -> String {
        if let existing = readUUID() { return existing }
        let generated = UUID().uuidString
        store(generated)
        return generated
    }

    private static func readUUID() -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data, let string = String(data: data, encoding: .utf8) else {
            return nil
        }
        return string
    }

    private static func store(_ uuidString: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        let updateStatus = SecItemUpdate(query as CFDictionary, [kSecValueData as String: Data(uuidString.utf8)] as CFDictionary)
        guard updateStatus == errSecItemNotFound else { return }
        var addQuery = query
        addQuery[kSecValueData as String] = Data(uuidString.utf8)
        SecItemAdd(addQuery as CFDictionary, nil)
    }
}
