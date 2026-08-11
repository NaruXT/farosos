import PersonStateMachine
import SwiftUI

/// Log en pantalla de las transiciones de la Máquina de estados A. Este
/// ticket no tiene BLE todavía — cuando llegue (#6/#7), los eventos de red
/// (emitido/recibido/descartado) se agregan a esta misma lista.
struct LogView: View {
    let entries: [LogEntry]

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter
    }()

    var body: some View {
        NavigationStack {
            List(entries.reversed()) { entry in
                HStack {
                    VStack(alignment: .leading) {
                        Text(label(for: entry.state))
                            .font(.body.bold())
                        Text("Secuencia \(entry.sequence)")
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
            .navigationTitle("Actividad")
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
}
