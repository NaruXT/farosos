import Foundation
import ParticipantRegistration

/// Guarda el perfil localmente y nada más — la subida real ocurre después,
/// disparada por `ConnectivityMonitor` dentro de `EmergencyViewModel`
/// (ADR-0003: desacoplada de este flujo, para que "continuar" nunca
/// requiera conectividad).
@MainActor
final class RegistrationViewModel: ObservableObject {
    @Published var name: String = ""
    @Published var contact: String = ""

    var canContinue: Bool {
        !trimmedName.isEmpty
    }

    private var trimmedName: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    func completeRegistration() {
        let trimmedContact = contact.trimmingCharacters(in: .whitespacesAndNewlines)
        let profile = ParticipantProfile(name: trimmedName, contact: trimmedContact.isEmpty ? nil : trimmedContact)
        KeychainParticipantStore.save(profile)
    }
}
