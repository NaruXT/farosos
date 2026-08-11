import Foundation
import PersonStateMachine

/// `Scheduler` de prueba: el tiempo solo avanza cuando el test llama a
/// `advance(by:)`, así los tests corren instantáneo sin depender de esperar
/// minutos reales. Compartido entre `PersonStateMachineTests` y
/// `BeaconRadioTests` (ambos necesitan pinnear timers con un reloj de
/// mentira) — vive en `Sources/` porque SPM no ofrece un mecanismo propio
/// para compartir fixtures solo entre test targets.
public final class FakeScheduler: Scheduler {
    private final class Entry: SchedulerToken {
        let id: Int
        let fireTime: TimeInterval
        let action: () -> Void
        init(id: Int, fireTime: TimeInterval, action: @escaping () -> Void) {
            self.id = id
            self.fireTime = fireTime
            self.action = action
        }
    }

    private var entries: [Entry] = []
    private var now: TimeInterval = 0
    private var nextId = 0

    public init() {}

    public func schedule(after seconds: TimeInterval, _ action: @escaping () -> Void) -> SchedulerToken {
        nextId += 1
        let entry = Entry(id: nextId, fireTime: now + seconds, action: action)
        entries.append(entry)
        return entry
    }

    public func cancel(_ token: SchedulerToken) {
        guard let entry = token as? Entry else { return }
        entries.removeAll { $0.id == entry.id }
    }

    /// Avanza el reloj y dispara, en orden, cualquier acción programada cuyo
    /// tiempo ya se cumplió.
    public func advance(by seconds: TimeInterval) {
        now += seconds
        while let due = entries.filter({ $0.fireTime <= now }).min(by: { $0.fireTime < $1.fireTime }) {
            entries.removeAll { $0.id == due.id }
            due.action()
        }
    }
}
