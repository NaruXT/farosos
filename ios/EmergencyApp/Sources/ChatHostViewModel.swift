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

        advertiser.onChatGuestConnected = { [weak self] in
            guard let self else { return }
            self.hasGuestConnected = true
            if let hostPublicKeyData = self.session.peerConnected() {
                self.advertiser.setChatHostPublicKey(hostPublicKeyData)
            }
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

    func isOwnMessage(_ message: ChatMessage) -> Bool {
        message.senderDeviceIdHash == ownDeviceIdHash
    }
}
