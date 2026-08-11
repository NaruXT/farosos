import Combine
import Foundation
import PacketCodec
import PersonStateMachine

struct LogEntry: Identifiable {
    let id = UUID()
    let timestamp: Date
    let state: PersonState
    let sequence: UInt8
}

/// Limitación conocida: el estado vive solo en memoria dentro de esta
/// instancia. La recuperación tardía desde `SILENCIO_TIMEOUT` funciona
/// mientras la app siga viva (probado en simulador), pero no sobrevive a un
/// cierre real del proceso — persistir el estado entre lanzamientos (para
/// que "abrir la app más tarde" también cubra ese caso) queda fuera de esta
/// ticket; es una decisión de diseño propia (dónde guardarlo, cómo re-armar
/// los timers en curso) que merece su propio issue.
@MainActor
final class EmergencyViewModel: ObservableObject {
    @Published private(set) var state: PersonState = .dormido
    @Published private(set) var logEntries: [LogEntry] = []
    /// Cuenta regresiva visible durante el sacudón (timer de gracia) y
    /// durante la ventana de confirmación — la misma UI sirve para ambas,
    /// solo cambia la duración de la que arranca.
    @Published private(set) var countdownSecondsRemaining: Int?

    private let machine: PersonStateMachine
    private let shakeDuration: TimeInterval
    private let confirmationWindow: TimeInterval
    private var countdownDeadline: Date?
    private var countdownTimer: Timer?

    /// Duraciones cortas por defecto para poder demostrar el flujo completo
    /// sin esperar minutos reales. En un dispositivo real se usarían ~120s
    /// de gracia y una ventana de 15-30 min — ambas configurables aquí mismo.
    init(shakeDuration: TimeInterval = 3, confirmationWindow: TimeInterval = 20) {
        self.shakeDuration = shakeDuration
        self.confirmationWindow = confirmationWindow
        machine = PersonStateMachine(
            scheduler: RealScheduler(),
            shakeDuration: shakeDuration,
            confirmationWindow: confirmationWindow
        )
        appendLogEntry()
        machine.onTransition = { [weak self] newState in
            self?.handleTransition(to: newState)
        }
    }

    func simulateEarthquake() { machine.simulateEarthquake() }
    func confirmOk() { machine.confirmOk() }
    func requestHelp() { machine.requestHelp() }

    private func handleTransition(to newState: PersonState) {
        state = newState
        appendLogEntry()

        switch newState {
        case .activoSinConfirmar:
            startCountdown(duration: shakeDuration)
        case .esperandoConfirmacion:
            startCountdown(duration: confirmationWindow)
        default:
            stopCountdown()
        }
    }

    private func appendLogEntry() {
        logEntries.append(LogEntry(timestamp: Date(), state: machine.state, sequence: machine.sequence))
    }

    private func startCountdown(duration: TimeInterval) {
        countdownDeadline = Date().addingTimeInterval(duration)
        countdownSecondsRemaining = Int(duration)
        countdownTimer?.invalidate()
        countdownTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tickCountdown() }
        }
    }

    private func tickCountdown() {
        guard let deadline = countdownDeadline else { return }
        let remaining = Int(ceil(deadline.timeIntervalSinceNow))
        countdownSecondsRemaining = max(remaining, 0)
        if remaining <= 0 { stopCountdown() }
    }

    private func stopCountdown() {
        countdownTimer?.invalidate()
        countdownTimer = nil
        countdownDeadline = nil
        countdownSecondsRemaining = nil
    }
}
