import Foundation

/// Reloj/programador inyectable para que `PersonStateMachine` pueda testear
/// el timer de gracia y el timeout sin depender de `Foundation.Timer` ni
/// esperar minutos reales. En producción, la app lo respalda con
/// `DispatchQueue`/`Timer`; en tests, un `Scheduler` de prueba avanza el
/// tiempo manualmente.
public protocol SchedulerToken {}

public protocol Scheduler {
    @discardableResult
    func schedule(after seconds: TimeInterval, _ action: @escaping () -> Void) -> SchedulerToken
    func cancel(_ token: SchedulerToken)
}
