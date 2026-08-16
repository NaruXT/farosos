import Foundation

/// Datos de identidad opt-in recogidos en el registro de la primera apertura
/// (ADR-0003) — nombre obligatorio, contacto opcional.
public struct ParticipantProfile: Equatable {
    public let name: String
    public let contact: String?

    public init(name: String, contact: String?) {
        self.name = name
        self.contact = contact
    }
}
