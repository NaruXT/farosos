import PacketCodec

/// Máquina de estados A (persona), definida en `spec/packet-format.md`.
public enum PersonState: Equatable {
    case dormido
    case activoSinConfirmar
    case esperandoConfirmacion
    case confirmadoOk
    case ayudaSolicitada
    case silencioTimeout

    /// El `Estado` de wire (`BeaconPacket.Status`) que corresponde a este
    /// estado de persona — única fuente de esa correspondencia, para que
    /// `PersonStateMachine.transition(to:)` no tenga que repetirla en cada
    /// call site.
    public var beaconStatus: BeaconPacket.Status {
        switch self {
        case .dormido, .activoSinConfirmar, .esperandoConfirmacion:
            return .sinConfirmar
        case .confirmadoOk:
            return .ok
        case .ayudaSolicitada:
            return .ayuda
        case .silencioTimeout:
            return .silencioTimeout
        }
    }
}
