import DirectChat
import Foundation

/// Lado víctima/host del canal de chat directo (#61/#62) — envuelve
/// `ChatHostSession` y la conecta a los eventos reales de `BleAdvertiser`.
/// Vive mientras `EmergencyViewModel` exista (no solo mientras la pantalla
/// de chat está abierta), porque el servicio GATT puede recibir una
/// conexión en cualquier momento mientras el propio estado pida ayuda —
/// la pantalla solo observa lo que ya está pasando, no controla la sesión.
@MainActor
final class ChatHostViewModel: ObservableObject {
    @Published private(set) var messages: [ChatMessage] = []
    @Published private(set) var hasGuestConnected = false

    let ownDeviceIdHash: Data
    private let session: ChatHostSession
    private let advertiser: BleAdvertiser

    init(ownDeviceIdHash: Data, advertiser: BleAdvertiser) {
        self.ownDeviceIdHash = ownDeviceIdHash
        self.advertiser = advertiser
        session = ChatHostSession(ownDeviceIdHash: ownDeviceIdHash, initialHistory: KeychainChatStore.history())
        messages = session.history

        session.onHistoryChanged = { [weak self] history in
            guard let self else { return }
            self.messages = history
            KeychainChatStore.save(history)
        }

        // Hallazgo de campo (#64): la clave propia ya no se genera acá -
        // `onChatHostPublicKeyRequested` la genera perezosamente en la
        // primera lectura real del rescatista (ver el comentario de ese
        // callback en `BleAdvertiser`). `onChatGuestConnected` sigue
        // existiendo solo para la UI (`hasGuestConnected`).
        advertiser.onChatHostPublicKeyRequested = { [weak self] in
            self?.session.peerConnected()
        }
        advertiser.onChatGuestConnected = { [weak self] in
            self?.hasGuestConnected = true
        }
        advertiser.onChatGuestPublicKeyWritten = { [weak self] data in
            guard let self, let sealedHistory = self.session.receivedPeerPublicKey(data) else { return }
            self.advertiser.notifyChatMessage(sealedHistory)
        }
        advertiser.onChatMessageWritten = { [weak self] data in
            self?.session.receivedEncryptedMessage(data)
        }
        advertiser.onChatGuestDisconnected = { [weak self] in
            guard let self else { return }
            self.hasGuestConnected = false
            self.session.peerDisconnected()
        }
    }

    func send(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let sealed = session.sendOwnMessage(trimmed, sentAt: UInt32(Date().timeIntervalSince1970)) else { return }
        advertiser.notifyChatMessage(sealed)
    }

    /// Este view model es siempre el lado víctima/host - "propio" es
    /// simplemente "de la víctima" (mismo criterio que Android, #63).
    func isOwnMessage(_ message: ChatMessage) -> Bool {
        message.fromVictim
    }
}
