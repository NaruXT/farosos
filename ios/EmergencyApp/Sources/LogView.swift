import NetworkRoleMachine
import PersonStateMachine
import SwiftUI

/// Log en pantalla de las transiciones de la Máquina de estados A y, desde
/// el ticket #13, de la Máquina B (rol de red). Sin BLE ni batería real
/// todavía en ninguna de las dos — el panel "Red" dispara las señales de la
/// Máquina B a mano, igual que "SIMULAR TERREMOTO" dispara la Máquina A.
struct LogView: View {
    let entries: [LogEntry]
    let networkRole: NetworkRole
    let onConnectivityDetected: () -> Void
    let onNothingPendingToSync: () -> Void
    let onLowBattery: () -> Void
    let onBatteryRecovered: () -> Void

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter
    }()

    var body: some View {
        NavigationStack {
            List {
                Section("Red (Máquina B) — \(label(for: networkRole))") {
                    Button("Conectividad detectada", action: onConnectivityDetected)
                    Button("Nada pendiente de sincronizar", action: onNothingPendingToSync)
                    Button("Simular batería < 15%", action: onLowBattery)
                    Button("Simular batería > 25% / cargando", action: onBatteryRecovered)
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
            return "De \(shortHex(deviceIdHash)) · TTL \(ttl) · Secuencia \(sequence)"
        case .duplicateDiscarded(let deviceIdHash, let nonce):
            return "De \(shortHex(deviceIdHash)) · Nonce \(nonce)"
        case .ttlExhausted(let deviceIdHash, let sequence):
            return "De \(shortHex(deviceIdHash)) · Secuencia \(sequence)"
        case .info(let message):
            return message
        }
    }

    private func shortHex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
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
