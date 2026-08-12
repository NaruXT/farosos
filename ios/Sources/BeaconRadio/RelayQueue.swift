import Foundation
import PacketCodec
import PersonStateMachine

/// Cola de retransmisión round-robin (decisión 9, `spec/packet-format.md`):
/// el propio beacon más los beacons ajenos pendientes de relay rotan con
/// una ventana fija, porque `CBPeripheralManager` solo permite exponer un
/// valor a la vez.
///
/// El propio beacon siempre está presente (se reemplaza en el lugar en
/// cada transición); el anuncio de gateway (Máquina B, `GATEWAY_ACTIVO`,
/// ticket #15) ocupa un segundo slot fijo con el mismo trato — presente
/// solo mientras se llame `updateGatewayAnnouncement`, nunca desalojado.
/// Los beacons ajenos tienen un tope: LRU simple normalmente, o la
/// prioridad de `BAJO_CONSUMO` cuando `isLowPower` está activo (ticket #15).
public final class RelayQueue {
    private struct Entry {
        let packet: BeaconPacket
        let slot: Slot

        enum Slot: Equatable {
            case own
            case gateway
            case foreign
        }
    }

    /// Identifica una entrada por su contenido lógico, no por su posición
    /// en el array — una posición cruda se desincroniza en cuanto el
    /// desalojo cambia el tamaño de `foreignEntries` entre rotaciones.
    private enum EntryKey: Equatable {
        case own
        case gateway
        case foreign(deviceIdHash: Data, nonce: UInt16)
    }

    public var onCurrentPacketChanged: ((BeaconPacket) -> Void)?

    /// Señal simple inyectada por quien gobierne la Máquina B — esta clase
    /// no depende de `NetworkRoleMachine` directamente. `false` (normal):
    /// desalojo LRU puro, igual que Fase 1. `true` (`BAJO_CONSUMO`): un
    /// `OK` se descarta primero; si no hay ninguno, no se desaloja nada —
    /// `SIN_CONFIRMAR`/`AYUDA`/`SILENCIO_TIMEOUT` nunca se pierden antes
    /// que un `OK`, aunque la cola quede momentáneamente sobre su tope.
    public var isLowPower = false

    private let scheduler: Scheduler
    private let window: TimeInterval
    private let foreignCapacity: Int

    private var ownEntry: Entry?
    private var gatewayEntry: Entry?
    private var foreignEntries: [Entry] = [] // más antigua al frente
    private var lastShownKey: EntryKey?
    private var rotationToken: SchedulerToken?

    public init(scheduler: Scheduler, window: TimeInterval = 1, foreignCapacity: Int = 20) {
        self.scheduler = scheduler
        self.window = window
        self.foreignCapacity = foreignCapacity
    }

    /// Reemplaza el propio beacon en su lugar en la cola — nunca se
    /// desaloja, solo se actualiza su contenido.
    public func updateOwnBeacon(_ packet: BeaconPacket) {
        ownEntry = Entry(packet: packet, slot: .own)
    }

    /// Ocupa el slot fijo de anuncio de gateway — mismo trato que el
    /// beacon propio: nunca se desaloja, se reemplaza en su lugar en cada
    /// actualización. Solo tiene sentido llamarlo mientras el nodo está en
    /// `GATEWAY_ACTIVO`.
    public func updateGatewayAnnouncement(_ packet: BeaconPacket) {
        gatewayEntry = Entry(packet: packet, slot: .gateway)
    }

    /// Libera el slot de gateway — se llama al salir de `GATEWAY_ACTIVO`.
    public func clearGatewayAnnouncement() {
        gatewayEntry = nil
    }

    /// Encola un beacon ajeno ya decrementado (`RelayPolicy`) para
    /// retransmitir. Si ya había una entrada con la misma clave
    /// (deviceIdHash + nonce), la reemplaza en vez de duplicarla.
    public func enqueueForeignBeacon(_ packet: BeaconPacket) {
        foreignEntries.removeAll {
            $0.packet.deviceIdHash == packet.deviceIdHash && $0.packet.nonce == packet.nonce
        }
        foreignEntries.append(Entry(packet: packet, slot: .foreign))
        evictIfNeeded()
    }

    private func evictIfNeeded() {
        guard foreignEntries.count > foreignCapacity else { return }
        guard isLowPower else {
            foreignEntries.removeFirst()
            return
        }
        // El OK más antiguo se descarta primero; si no hay ninguno, no se
        // desaloja nada — proteger lo urgente importa más que respetar el
        // tope exacto.
        if let index = foreignEntries.firstIndex(where: { $0.packet.status == .ok }) {
            foreignEntries.remove(at: index)
        }
    }

    /// Expone la primera entrada de inmediato y arranca la rotación.
    public func start() {
        notifyCurrent()
        scheduleNextRotation()
    }

    public func stop() {
        if let token = rotationToken {
            scheduler.cancel(token)
        }
        rotationToken = nil
    }

    private var allEntries: [Entry] {
        (ownEntry.map { [$0] } ?? []) + (gatewayEntry.map { [$0] } ?? []) + foreignEntries
    }

    private func key(for entry: Entry) -> EntryKey {
        switch entry.slot {
        case .own: return .own
        case .gateway: return .gateway
        case .foreign: return .foreign(deviceIdHash: entry.packet.deviceIdHash, nonce: entry.packet.nonce)
        }
    }

    private func scheduleNextRotation() {
        rotationToken = scheduler.schedule(after: window) { [weak self] in
            self?.rotate()
        }
    }

    /// Avanza a la entrada siguiente a la última mostrada, ubicándola por
    /// clave en vez de por índice: si esa entrada ya no existe (se
    /// desalojó mientras era la actual), reanuda desde el principio en vez
    /// de saltarse — a ciegas — la que quedó justo después en el array.
    private func rotate() {
        let entries = allEntries
        guard !entries.isEmpty else {
            scheduleNextRotation()
            return
        }
        let currentPosition = lastShownKey.flatMap { shownKey in
            entries.firstIndex { key(for: $0) == shownKey }
        }
        let nextPosition = ((currentPosition ?? -1) + 1) % entries.count
        notify(entries[nextPosition])
        scheduleNextRotation()
    }

    private func notifyCurrent() {
        guard let first = allEntries.first else { return }
        notify(first)
    }

    private func notify(_ entry: Entry) {
        lastShownKey = key(for: entry)
        onCurrentPacketChanged?(entry.packet)
    }
}
