import PersonStateMachine
import SwiftUI

struct EmergencyView: View {
    @StateObject private var viewModel = EmergencyViewModel()
    @State private var showingLog = false
    @State private var showingKnownCases = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                switch viewModel.state {
                case .dormido:
                    DormidoScreen(onSimulate: viewModel.simulateEarthquake)
                case .activoSinConfirmar:
                    ActivoSinConfirmarScreen(secondsRemaining: viewModel.countdownSecondsRemaining)
                case .esperandoConfirmacion:
                    EsperandoConfirmacionScreen(
                        secondsRemaining: viewModel.countdownSecondsRemaining,
                        onConfirmOk: viewModel.confirmOk,
                        onRequestHelp: viewModel.requestHelp
                    )
                case .confirmadoOk:
                    ConfirmadoOkScreen()
                case .ayudaSolicitada:
                    AyudaSolicitadaScreen(onCancel: viewModel.confirmOk)
                case .silencioTimeout:
                    SilencioTimeoutScreen(onConfirmOk: viewModel.confirmOk)
                }
            }
            .padding()
            .navigationTitle("Farosos")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Casos") { showingKnownCases = true }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Log") { showingLog = true }
                }
            }
            .sheet(isPresented: $showingLog) {
                LogView(
                    entries: viewModel.logEntries,
                    networkRole: viewModel.networkRole
                )
            }
            .sheet(isPresented: $showingKnownCases) {
                KnownCasesView(
                    viewModel: viewModel.makeKnownCasesViewModel(),
                    ownState: viewModel.state
                )
            }
        }
    }
}

private struct DormidoScreen: View {
    let onSimulate: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Text("Todo tranquilo por ahora.")
                .foregroundStyle(.secondary)
            Button("SIMULAR TERREMOTO", action: onSimulate)
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
        }
    }
}

private struct ActivoSinConfirmarScreen: View {
    let secondsRemaining: Int?

    var body: some View {
        VStack(spacing: 16) {
            Text("SISMO DETECTADO")
                .font(.title.bold())
            if let secondsRemaining {
                Text("\(secondsRemaining)")
                    .font(.system(size: 40, weight: .bold, design: .monospaced))
                    .foregroundStyle(.secondary)
            }
            Text("Difundiendo beacon…")
                .foregroundStyle(.secondary)
                .monospaced()
        }
    }
}

private struct EsperandoConfirmacionScreen: View {
    let secondsRemaining: Int?
    let onConfirmOk: () -> Void
    let onRequestHelp: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            if let secondsRemaining {
                Text("\(secondsRemaining)")
                    .font(.system(size: 48, weight: .bold, design: .monospaced))
            }
            Text("¿Estás bien?")
                .font(.title2.bold())
            VStack(spacing: 12) {
                Button("ESTOY BIEN", action: onConfirmOk)
                    .buttonStyle(.borderedProminent)
                    .tint(.green)
                    .controlSize(.large)
                Button("NECESITO AYUDA", action: onRequestHelp)
                    .buttonStyle(.borderedProminent)
                    .tint(.red)
                    .controlSize(.large)
            }
        }
    }
}

private struct ConfirmadoOkScreen: View {
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 48))
                .foregroundStyle(.green)
            Text("Estás marcado como BIEN")
                .font(.title2.bold())
            Text("Tu estado quedó registrado.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
        }
    }
}

private struct AyudaSolicitadaScreen: View {
    let onCancel: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 48))
                .foregroundStyle(.red)
            Text("AYUDA SOLICITADA")
                .font(.title2.bold())
                .foregroundStyle(.red)
            Text("Tu solicitud de ayuda quedó registrada.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            Button("YA ESTOY BIEN / CANCELAR", action: onCancel)
                .buttonStyle(.borderedProminent)
                .tint(.green)
                .controlSize(.large)
        }
    }
}

private struct SilencioTimeoutScreen: View {
    let onConfirmOk: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "clock.badge.exclamationmark.fill")
                .font(.system(size: 48))
                .foregroundStyle(.orange)
            Text("No confirmaste a tiempo")
                .font(.title2.bold())
            Text("Es posible que otros estén buscándote. Puedes confirmar ahora, aunque haya pasado la ventana.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            Button("ESTOY BIEN (confirmación tardía)", action: onConfirmOk)
                .buttonStyle(.borderedProminent)
                .tint(.green)
                .controlSize(.large)
        }
    }
}
