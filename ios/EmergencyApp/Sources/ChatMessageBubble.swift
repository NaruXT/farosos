import DirectChat
import SwiftUI

/// Burbuja de un mensaje del chat directo (#61/#62) — compartida entre
/// `ChatView` (lado rescatista) y `ChatHostView` (lado víctima). Ambas
/// pantallas necesitan la misma burbuja pero se alinean a `ChatViewModel`/
/// `ChatHostViewModel` respectivamente (tipos distintos, cada uno con su
/// propio `isOwnMessage`), así que solo la burbuja en sí se comparte, no
/// la pantalla completa.
struct ChatMessageBubble: View {
    let message: ChatMessage
    let isOwn: Bool

    var body: some View {
        HStack {
            if isOwn { Spacer() }
            Text(message.text)
                .padding(10)
                .background(isOwn ? Color.accentColor.opacity(0.2) : Color(.secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 12))
            if !isOwn { Spacer() }
        }
    }
}
