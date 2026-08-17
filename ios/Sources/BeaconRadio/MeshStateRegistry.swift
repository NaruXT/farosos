import Foundation
import PacketCodec

/// Estado más reciente conocido de un participante de la malla — lo que un
/// gateway sube a `mesh_states` (ticket #31, ADR-0002).
public struct MeshParticipantState: Equatable {
    public let deviceIdHash: Data
    public let status: BeaconPacket.Status
    public let latitudeE7: Int32
    public let longitudeE7: Int32
    public let timestamp: UInt32
    public let sequence: UInt8

    public init(deviceIdHash: Data, status: BeaconPacket.Status, latitudeE7: Int32, longitudeE7: Int32, timestamp: UInt32, sequence: UInt8) {
        self.deviceIdHash = deviceIdHash
        self.status = status
        self.latitudeE7 = latitudeE7
        self.longitudeE7 = longitudeE7
        self.timestamp = timestamp
        self.sequence = sequence
    }
}

/// Guarda el último estado conocido de la malla por `deviceIdHash` — "lo que
/// este teléfono sabe", para subir al backend de agregación al entrar a
/// `GATEWAY_ACTIVO` (ticket #31). Se alimenta desde dos puntos en la capa de
/// app, sin importar el rol de red actual (para que el snapshot inicial al
/// activarse como gateway ya incluya lo visto antes de convertirse en
/// gateway): el seam de `handleReceivedPacketData` (beacons ajenos) y el de
/// `refreshAdvertisedBeacon` (el propio estado — nunca llega por el primer
/// camino porque se auto-descarta como duplicado al rebotar, decisión 12).
public final class MeshStateRegistry {
    /// Se dispara solo cuando `update` acepta un estado nuevo — no en cada
    /// llamada.
    public var onStateUpdated: ((MeshParticipantState) -> Void)?

    private var states: [Data: MeshParticipantState] = [:]

    public init() {}

    /// Acepta el paquete solo si su secuencia es estrictamente más nueva que
    /// la que ya se conocía para ese `deviceIdHash` (decisión: nueva
    /// Secuencia > vieja, sin manejo de wraparound — fuera de alcance de
    /// este ticket). Devuelve si se aceptó.
    @discardableResult
    public func update(with packet: BeaconPacket) -> Bool {
        if let existing = states[packet.deviceIdHash], existing.sequence >= packet.sequence {
            return false
        }
        let state = MeshParticipantState(
            deviceIdHash: packet.deviceIdHash,
            status: packet.status,
            latitudeE7: packet.latitudeE7,
            longitudeE7: packet.longitudeE7,
            timestamp: packet.timestamp,
            sequence: packet.sequence
        )
        states[packet.deviceIdHash] = state
        onStateUpdated?(state)
        return true
    }

    public func allStates() -> [MeshParticipantState] { Array(states.values) }
}
