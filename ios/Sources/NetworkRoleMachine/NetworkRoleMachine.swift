/// Máquina de estados B (rol de red del teléfono, `spec/packet-format.md`,
/// issue #12). Corre en paralelo a `PersonStateMachine` — un teléfono puede
/// estar en cualquier combinación de ambas, no se combinan en un único enum.
///
/// Sin `Scheduler`: a diferencia de la Máquina A, ninguna transición depende
/// de un timer — todas llegan como señales externas (app activa,
/// conectividad, nada pendiente, lectura de batería), inyectadas por quien
/// use esta clase.
public final class NetworkRoleMachine {
    /// Por debajo de este porcentaje, el teléfono entra a `BAJO_CONSUMO`.
    private static let lowBatteryThreshold = 15
    /// Por encima de este porcentaje (o cargando), sale de `BAJO_CONSUMO`.
    private static let recoveryThreshold = 25

    public private(set) var state: NetworkRole = .apagado

    /// Se dispara con cada transición — la UI lo usa para el log en pantalla.
    public var onTransition: ((NetworkRole) -> Void)?

    public init() {}

    /// La app pasa a primer plano/se activa — único disparador de salida de
    /// `APAGADO`. No requiere confirmación explícita del usuario, a
    /// diferencia de la Máquina A.
    public func appActivated() {
        guard state == .apagado else { return }
        transition(.soloRetransmite)
    }

    /// El teléfono detecta conectividad real hacia internet (no solo BLE).
    public func connectivityDetected() {
        guard state == .soloRetransmite else { return }
        transition(.gatewayActivo)
    }

    /// No queda ningún beacon ajeno pendiente de agregación/anuncio.
    public func nothingPendingToSync() {
        guard state == .gatewayActivo else { return }
        transition(.sincronizadoIdle)
    }

    /// Volvió a quedar algo pendiente (p. ej. llegó un beacon ajeno nuevo)
    /// mientras el teléfono estaba tranquilo en `SINCRONIZADO_IDLE` — único
    /// camino de vuelta a `GATEWAY_ACTIVO`, sin el cual la máquina se
    /// quedaba atascada ignorando información nueva.
    public func somethingPendingToSync() {
        guard state == .sincronizadoIdle else { return }
        transition(.gatewayActivo)
    }

    /// Se llama con cada lectura de batería. Si ya está en `BAJO_CONSUMO`,
    /// solo evalúa la condición de recuperación (batería > 25% O cargando —
    /// cargando recupera sin importar el porcentaje, por eso se revisa antes
    /// que nada, no como un `else` del umbral bajo). Si no está en
    /// `BAJO_CONSUMO`, evalúa si debe entrar por batería < 15%.
    public func updateBattery(percent: Int, isCharging: Bool) {
        if state == .bajoConsumo {
            if percent > Self.recoveryThreshold || isCharging {
                transition(.soloRetransmite)
            }
            return
        }
        if percent < Self.lowBatteryThreshold {
            transition(.bajoConsumo)
        }
    }

    private func transition(_ newState: NetworkRole) {
        state = newState
        onTransition?(newState)
    }
}
