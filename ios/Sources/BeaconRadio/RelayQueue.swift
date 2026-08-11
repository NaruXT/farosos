import Foundation
import PacketCodec
import PersonStateMachine

/// Cola de retransmisión round-robin (decisión 9, `spec/packet-format.md`):
/// el propio beacon más los beacons ajenos pendientes de relay rotan con
/// una ventana fija, porque `CBPeripheralManager` solo permite exponer un
/// valor a la vez. Sin priorización por estado en esta fase — todas las
/// entradas tienen el mismo turno.
///
/// El propio beacon siempre está presente (se reemplaza en el lugar en
/// cada transición); los beacons ajenos tienen un tope con desalojo LRU
/// simple cuando se llena (sin la priorización de `BAJO_CONSUMO`, que
/// pertenece a la Máquina B de Fase 2).
public final class RelayQueue {
    private struct Entry {
        let packet: BeaconPacket
        let isOwnBeacon: Bool
    }

    /// Identifica una entrada por su contenido lógico, no por su posición
    /// en el array — una posición cruda se desincroniza en cuanto el
    /// desalojo LRU cambia el tamaño de `foreignEntries` entre rotaciones.
    private enum EntryKey: Equatable {
        case own
        case foreign(deviceIdHash: Data, nonce: UInt16)
    }

    public var onCurrentPacketChanged: ((BeaconPacket) -> Void)?

    private let scheduler: Scheduler
    private let window: TimeInterval
    private let foreignCapacity: Int

    private var ownEntry: Entry?
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
        ownEntry = Entry(packet: packet, isOwnBeacon: true)
    }

    /// Encola un beacon ajeno ya decrementado (`RelayPolicy`) para
    /// retransmitir. Si ya había una entrada con la misma clave
    /// (deviceIdHash + nonce), la reemplaza en vez de duplicarla.
    public func enqueueForeignBeacon(_ packet: BeaconPacket) {
        foreignEntries.removeAll {
            $0.packet.deviceIdHash == packet.deviceIdHash && $0.packet.nonce == packet.nonce
        }
        foreignEntries.append(Entry(packet: packet, isOwnBeacon: false))
        if foreignEntries.count > foreignCapacity {
            foreignEntries.removeFirst()
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
        (ownEntry.map { [$0] } ?? []) + foreignEntries
    }

    private func key(for entry: Entry) -> EntryKey {
        entry.isOwnBeacon ? .own : .foreign(deviceIdHash: entry.packet.deviceIdHash, nonce: entry.packet.nonce)
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
