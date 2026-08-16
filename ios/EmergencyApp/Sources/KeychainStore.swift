import Foundation
import Security

/// Envoltorio genérico de lectura/escritura de un valor string en Keychain,
/// bajo el servicio compartido de la app. Única fuente de las queries
/// `SecItemCopyMatching`/`SecItemAdd`/`SecItemUpdate` — `KeychainDeviceIdentity`
/// y `KeychainParticipantStore` la usan en vez de repetir el boilerplate.
enum KeychainStore {
    private static let service = "com.farosos.EmergencyApp"

    static func read(account: String) -> String? {
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

    static func write(_ value: String, account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        let updateStatus = SecItemUpdate(query as CFDictionary, [kSecValueData as String: Data(value.utf8)] as CFDictionary)
        guard updateStatus == errSecItemNotFound else { return }
        var addQuery = query
        addQuery[kSecValueData as String] = Data(value.utf8)
        SecItemAdd(addQuery as CFDictionary, nil)
    }
}
