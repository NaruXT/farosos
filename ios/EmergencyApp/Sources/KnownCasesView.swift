import BeaconRadio
import PacketCodec
import PersonStateMachine
import SwiftUI

/// Pantalla nueva (#55/#57): lista los casos `AYUDA`/`SILENCIO_TIMEOUT`
/// conocidos por este teléfono con dos acciones independientes por caso.
/// Ambas quedan ocultas mientras el propio estado (Máquina A) esté pidiendo
/// ayuda (`PersonState.isRequestingHelp`) — quien está pidiendo ayuda no
/// puede atender casos ajenos. Recibe `ownState` como valor simple (no el
/// `EmergencyViewModel` completo) — todo lo que esta pantalla necesita saber
/// del estado propio es esa única señal.
struct KnownCasesView: View {
    @ObservedObject var viewModel: KnownCasesViewModel
    let ownState: PersonState
    /// Callback en vez de navegación propia — esta pantalla no sabe cómo
    /// construir un `ChatViewModel` (necesita el `CBPeripheral` del caso,
    /// que vive en `EmergencyViewModel.chatPeerDirectory`), solo avisa qué
    /// caso se eligió.
    let onOpenChat: (Data) -> Void

    var body: some View {
        NavigationStack {
            List {
                if viewModel.cases.isEmpty {
                    Text("No hay casos conocidos por este teléfono todavía.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(viewModel.cases, id: \.deviceIdHash) { caseState in
                        caseRow(caseState)
                    }
                }
            }
            .navigationTitle("Casos conocidos")
            .onAppear { viewModel.refresh() }
        }
    }

    @ViewBuilder
    private func caseRow(_ caseState: MeshParticipantState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(caseState.deviceIdHash.shortHex)
                .font(.body.bold())
                .monospaced()
            Text(statusLabel(caseState.status))
                .font(.caption)
                .foregroundStyle(.secondary)
            if !ownState.isRequestingHelp {
                HStack {
                    Button("Voy a socorrer") {
                        viewModel.markAttending(caseState)
                    }
                    .buttonStyle(.bordered)
                    Button("Marcar como resuelto") {
                        viewModel.markResolved(caseState)
                    }
                    .buttonStyle(.borderedProminent)
                    Button("Abrir chat") {
                        onOpenChat(caseState.deviceIdHash)
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func statusLabel(_ status: BeaconPacket.Status) -> String {
        switch status {
        case .ayuda: return "Pidiendo ayuda"
        case .silencioTimeout: return "Sin respuesta"
        case .sinConfirmar, .ok, .gatewayDisponible: return "—"
        }
    }
}
