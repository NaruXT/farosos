import SwiftUI

/// Pantalla de registro opt-in mostrada solo en la primera apertura
/// (ADR-0003) — pide nombre (obligatorio) y contacto (opcional), y nunca
/// bloquea "continuar" por falta de conectividad.
struct RegistrationView: View {
    @StateObject private var viewModel = RegistrationViewModel()
    let onCompleted: () -> Void

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                VStack(spacing: 8) {
                    Text("Antes de empezar")
                        .font(.title2.bold())
                    Text("Tu nombre ayuda a que el equipo de rescate sepa quién eres si tu teléfono llega a formar parte de la malla.")
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                }
                VStack(spacing: 12) {
                    TextField("Nombre", text: $viewModel.name)
                        .textFieldStyle(.roundedBorder)
                    TextField("Contacto (opcional)", text: $viewModel.contact)
                        .textFieldStyle(.roundedBorder)
                        .keyboardType(.phonePad)
                }
                Button("Continuar") {
                    viewModel.completeRegistration()
                    onCompleted()
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(!viewModel.canContinue)
            }
            .padding()
            .navigationTitle("Farosos")
        }
    }
}
