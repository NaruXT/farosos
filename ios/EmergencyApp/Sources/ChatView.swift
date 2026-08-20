import SwiftUI

/// Pantalla nueva del canal de chat directo (#61/#62) — lista de mensajes
/// + input de texto libre, sin frases predefinidas (decisión explícita de
/// la sesión de `/grilling`).
struct ChatView: View {
    @ObservedObject var viewModel: ChatViewModel
    @State private var draft = ""

    var body: some View {
        VStack(spacing: 0) {
            if let errorMessage = viewModel.errorMessage {
                Text(errorMessage)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .padding(.horizontal)
            }
            Text(viewModel.isConnected ? "Conectado" : "Conectando…")
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.top, 4)
            // Diagnóstico temporal de campo (#64) - ver el comentario de
            // `ChatViewModel.debugStatus`.
            Text(viewModel.debugStatus)
                .font(.caption2)
                .foregroundStyle(.orange)
                .padding(.horizontal)
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(Array(viewModel.messages.enumerated()), id: \.offset) { offset, message in
                            ChatMessageBubble(message: message, isOwn: viewModel.isOwnMessage(message))
                                .id(offset)
                        }
                    }
                    .padding()
                }
                // Fija la lista en el último mensaje al llegar uno nuevo -
                // mismo criterio en ambas plataformas (hallazgo de campo
                // #64: sin esto, había que hacer scroll manual para ver la
                // conversación al día).
                .onChange(of: viewModel.messages.count) { newCount in
                    guard newCount > 0 else { return }
                    withAnimation { proxy.scrollTo(newCount - 1, anchor: .bottom) }
                }
            }
            HStack {
                TextField("Escribí un mensaje…", text: $draft)
                    .textFieldStyle(.roundedBorder)
                Button("Enviar") {
                    viewModel.send(draft)
                    draft = ""
                }
                .disabled(!viewModel.isConnected)
            }
            .padding()
        }
        .navigationTitle("Chat")
        .onDisappear { viewModel.stop() }
    }
}
