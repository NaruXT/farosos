import SwiftUI

/// Pantalla de chat del lado víctima/host (#61/#62) — misma lista de
/// mensajes + input que `ChatView` (lado rescatista, comparten
/// `ChatMessageBubble`), pero atada a `ChatHostViewModel` en vez de
/// `ChatViewModel`: los dos lados del canal tienen formas distintas de
/// conectar (host espera, rescatista se conecta activamente) así que no
/// comparten un mismo tipo de vista, pese a que la UI se ve casi igual.
struct ChatHostView: View {
    @ObservedObject var viewModel: ChatHostViewModel
    @State private var draft = ""

    var body: some View {
        VStack(spacing: 0) {
            Text(viewModel.hasGuestConnected ? "Rescatista conectado" : "Esperando a que alguien se conecte…")
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.top, 4)
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
                // Ver el comentario equivalente en `ChatView` (hallazgo de
                // campo #64) - mismo criterio en ambas plataformas.
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
                .disabled(!viewModel.hasGuestConnected)
            }
            .padding()
        }
        .navigationTitle("Chat")
    }
}
