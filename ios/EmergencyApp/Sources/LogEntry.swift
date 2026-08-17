import Foundation
import NetworkRoleMachine
import PersonStateMachine

/// Entrada del log en pantalla (decisión 11, `spec/packet-format.md`):
/// mecanismo principal de verificación en campo, no solo consola/Xcode.
struct LogEntry: Identifiable {
    let id = UUID()
    let timestamp: Date
    let kind: Kind

    enum Kind {
        case transition(state: PersonState, sequence: UInt8)
        case beaconReceived(deviceIdHash: Data, ttl: UInt8, sequence: UInt8)
        case duplicateDiscarded(deviceIdHash: Data, nonce: UInt16)
        case ttlExhausted(deviceIdHash: Data, sequence: UInt8)
        case info(message: String)
        /// Transición de la Máquina B (rol de red, issue #12/#13) — se
        /// distingue en `LogView` de los `.transition` de la Máquina A.
        case networkRoleTransition(role: NetworkRole)
    }
}

extension Data {
    /// Formato compacto de un `deviceIdHash` para mostrarlo en el log
    /// (`LogView`) o loguearlo desde el propio `EmergencyViewModel` (#34) —
    /// vive junto a `LogEntry` en vez de en `LogView` para no obligar a la
    /// capa de view model a depender de un tipo `View`.
    var shortHex: String {
        map { String(format: "%02x", $0) }.joined()
    }
}
