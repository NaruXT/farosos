/// Máquina de estados B (rol de red del teléfono), definida en
/// `spec/packet-format.md` — independiente de la Máquina A (estado de la
/// persona). Fase 2 (ticket #13): foreground-only, sin BLE ni acceso real a
/// batería/conectividad; las señales llegan inyectadas desde afuera.
public enum NetworkRole: Equatable {
    case apagado
    case soloRetransmite
    case gatewayActivo
    case sincronizadoIdle
    case bajoConsumo
}
