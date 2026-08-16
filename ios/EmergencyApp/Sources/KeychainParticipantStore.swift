import Foundation
import ParticipantRegistration

/// Persiste el perfil de registro opt-in (ADR-0003) en Keychain, mismo
/// patrón que `KeychainDeviceIdentity`. Vive en la capa de app, no en el
/// paquete SPM testeado — Keychain requiere el entorno real de la app.
enum KeychainParticipantStore {
    private static let nameAccount = "participantName"
    private static let contactAccount = "participantContact"
    private static let uploadedAccount = "participantUploaded"

    static func hasRegisteredProfile() -> Bool {
        KeychainStore.read(account: nameAccount) != nil
    }

    /// Guarda el perfil localmente sin marcarlo como subido — queda
    /// pendiente hasta que `ParticipantUploadCoordinator` confirme la
    /// subida vía `markUploaded()`.
    static func save(_ profile: ParticipantProfile) {
        KeychainStore.write(profile.name, account: nameAccount)
        if let contact = profile.contact {
            KeychainStore.write(contact, account: contactAccount)
        }
    }

    /// El perfil guardado si todavía no se subió a `participants` — nil si
    /// no hay perfil registrado, o si ya se subió con éxito.
    static func pendingProfile() -> ParticipantProfile? {
        guard KeychainStore.read(account: uploadedAccount) == nil,
              let name = KeychainStore.read(account: nameAccount) else {
            return nil
        }
        return ParticipantProfile(name: name, contact: KeychainStore.read(account: contactAccount))
    }

    static func markUploaded() {
        KeychainStore.write("true", account: uploadedAccount)
    }
}
