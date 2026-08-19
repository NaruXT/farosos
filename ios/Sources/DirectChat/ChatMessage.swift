import Foundation

/// Un mensaje del canal de chat directo (#61) — texto libre, sin frases
/// predefinidas (decisión explícita de la sesión de `/grilling`).
/// `senderDeviceIdHash` identifica de quién es, no si es "propio" o
/// "ajeno" — eso lo decide quien renderiza, comparando contra su propio
/// hash.
public struct ChatMessage: Equatable, Codable {
    public let senderDeviceIdHash: Data
    public let text: String
    public let sentAt: UInt32

    public init(senderDeviceIdHash: Data, text: String, sentAt: UInt32) {
        self.senderDeviceIdHash = senderDeviceIdHash
        self.text = text
        self.sentAt = sentAt
    }
}
