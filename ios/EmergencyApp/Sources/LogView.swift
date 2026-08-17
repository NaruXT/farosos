import NetworkRoleMachine
import PersonStateMachine
import SwiftUI

/// Log en pantalla de las transiciones de la Máquina de estados A y, desde
/// el ticket #13, de la Máquina B (rol de red), poblada solo por señales
/// reales del sistema (`BatteryMonitor`/`ConnectivityMonitor`/`RelayQueue`,
/// tickets #20/#24).
struct LogView: View {
    let entries: [LogEntry]
    let networkRole: NetworkRole

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter
    }()

    var body: some View {
        NavigationStack {
            List {
                Section("Red (Máquina B)") {
                    HStack(spacing: 12) {
                        let presentation = presentation(for: networkRole)
                        Image(systemName: presentation.icon)
                            .foregroundStyle(presentation.tint)
                            .imageScale(.large)
                        Text(label(for: networkRole))
                            .font(.body.bold())
                        Spacer()
                    }
                }
                Section("Actividad") {
                    ForEach(entries.reversed()) { entry in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(title(for: entry.kind))
                                    .font(.body.bold())
                                Text(detail(for: entry.kind))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .monospaced()
                            }
                            Spacer()
                            Text(Self.timeFormatter.string(from: entry.timestamp))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .monospaced()
                        }
                    }
                }
            }
            .navigationTitle("Actividad")
        }
    }

    private func title(for kind: LogEntry.Kind) -> String {
        switch kind {
        case .transition(let state, _): return label(for: state)
        case .beaconReceived: return "RECIBIDO"
        case .duplicateDiscarded: return "DESCARTADO POR DUPLICADO"
        case .ttlExhausted: return "DESCARTADO POR TTL AGOTADO"
        case .info: return "INFO"
        case .networkRoleTransition(let role): return "RED: \(label(for: role))"
        }
    }

    private func detail(for kind: LogEntry.Kind) -> String {
        switch kind {
        case .transition(_, let sequence):
            return "Secuencia \(sequence)"
        case .networkRoleTransition:
            return "Máquina B (rol de red)"
        case .beaconReceived(let deviceIdHash, let ttl, let sequence):
            return "De \(deviceIdHash.shortHex) · TTL \(ttl) · Secuencia \(sequence)"
        case .duplicateDiscarded(let deviceIdHash, let nonce):
            return "De \(deviceIdHash.shortHex) · Nonce \(nonce)"
        case .ttlExhausted(let deviceIdHash, let sequence):
            return "De \(deviceIdHash.shortHex) · Secuencia \(sequence)"
        case .info(let message):
            return message
        }
    }

    private func presentation(for role: NetworkRole) -> (icon: String, tint: Color) {
        switch role {
        case .apagado: return ("power", .secondary)
        case .soloRetransmite: return ("arrow.triangle.2.circlepath", .blue)
        case .gatewayActivo: return ("antenna.radiowaves.left.and.right", .green)
        case .sincronizadoIdle: return ("checkmark.circle", .secondary)
        case .bajoConsumo: return ("battery.25", .orange)
        }
    }

    private func label(for state: PersonState) -> String {
        switch state {
        case .dormido: return "DORMIDO"
        case .activoSinConfirmar: return "ACTIVO_SIN_CONFIRMAR"
        case .esperandoConfirmacion: return "ESPERANDO_CONFIRMACION"
        case .confirmadoOk: return "CONFIRMADO_OK"
        case .ayudaSolicitada: return "AYUDA_SOLICITADA"
        case .silencioTimeout: return "SILENCIO_TIMEOUT"
        }
    }

    private func label(for role: NetworkRole) -> String {
        switch role {
        case .apagado: return "APAGADO"
        case .soloRetransmite: return "SOLO_RETRANSMITE"
        case .gatewayActivo: return "GATEWAY_ACTIVO"
        case .sincronizadoIdle: return "SINCRONIZADO_IDLE"
        case .bajoConsumo: return "BAJO_CONSUMO"
        }
    }
}
