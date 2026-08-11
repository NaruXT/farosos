import Foundation
import PacketCodec

/// Máquina de estados A (persona). No conoce BLE ni UI — solo el estado, las
/// transiciones válidas, y el reloj inyectado para el timer de gracia y el
/// timeout. `status`/`sequence` reutilizan los tipos de `PacketCodec` (#2)
/// para que quien construya el `BeaconPacket` real (capa BLE, fuera de esta
/// ticket) no tenga que traducir un vocabulario propio.
public final class PersonStateMachine {
    public private(set) var state: PersonState = .dormido
    public private(set) var status: BeaconPacket.Status = .sinConfirmar
    public private(set) var sequence: UInt8 = 0

    /// Se dispara con cada transición, manual o automática (fin del sacudón,
    /// timeout) — la UI lo usa para actualizar la pantalla y el log sin tener
    /// que adivinar cuándo un timer disparó una transición por su cuenta.
    public var onTransition: ((PersonState) -> Void)?

    private let scheduler: Scheduler
    private let shakeDuration: TimeInterval
    private let confirmationWindow: TimeInterval
    private var pendingToken: SchedulerToken?

    public init(
        scheduler: Scheduler,
        shakeDuration: TimeInterval,
        confirmationWindow: TimeInterval
    ) {
        self.scheduler = scheduler
        self.shakeDuration = shakeDuration
        self.confirmationWindow = confirmationWindow
    }

    public func simulateEarthquake() {
        guard state == .dormido else { return }
        transition(to: .activoSinConfirmar)
        pendingToken = scheduler.schedule(after: shakeDuration) { [weak self] in
            self?.shakeEnded()
        }
    }

    private func shakeEnded() {
        guard state == .activoSinConfirmar else { return }
        transition(to: .esperandoConfirmacion)
        pendingToken = scheduler.schedule(after: confirmationWindow) { [weak self] in
            self?.timeoutFired()
        }
    }

    private func timeoutFired() {
        guard state == .esperandoConfirmacion else { return }
        transition(to: .silencioTimeout)
    }

    public func confirmOk() {
        guard state == .esperandoConfirmacion || state == .silencioTimeout || state == .ayudaSolicitada else { return }
        cancelPending()
        transition(to: .confirmadoOk)
    }

    public func requestHelp() {
        guard state == .esperandoConfirmacion else { return }
        cancelPending()
        transition(to: .ayudaSolicitada)
    }

    private func cancelPending() {
        if let token = pendingToken {
            scheduler.cancel(token)
            pendingToken = nil
        }
    }

    private func transition(to newState: PersonState) {
        state = newState
        status = newState.beaconStatus
        sequence = sequence &+ 1
        onTransition?(newState)
    }
}
