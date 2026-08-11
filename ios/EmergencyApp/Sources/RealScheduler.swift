import Foundation
import PersonStateMachine

/// `Scheduler` de producción, respaldado por `DispatchQueue.main`. El
/// `PersonStateMachine` en sí no depende de `Dispatch` — solo esta capa de
/// app, para poder mantener el módulo testeado libre de esa dependencia.
final class RealScheduler: Scheduler {
    private final class Entry: SchedulerToken {
        let workItem: DispatchWorkItem
        init(workItem: DispatchWorkItem) { self.workItem = workItem }
    }

    func schedule(after seconds: TimeInterval, _ action: @escaping () -> Void) -> SchedulerToken {
        let workItem = DispatchWorkItem(block: action)
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: workItem)
        return Entry(workItem: workItem)
    }

    func cancel(_ token: SchedulerToken) {
        (token as? Entry)?.workItem.cancel()
    }
}
