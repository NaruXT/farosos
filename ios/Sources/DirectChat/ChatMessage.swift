import Foundation

/// Un mensaje del canal de chat directo (#61) — texto libre, sin frases
/// predefinidas (decisión explícita de la sesión de `/grilling`).
///
/// Reescrito durante la verificación de campo real de #64: la primera
/// versión identificaba al remitente con `senderDeviceIdHash: Data` y
/// serializaba como JSON - incompatible con Android (`ChatMessage`/
/// `ChatMessageWireFormat`, #63), que usa `fromVictim: Boolean` (alcanza
/// porque el diseño ya fijó exactamente dos partes por conexión - víctima y
/// un rescatista, #61) y un formato de texto propio, no JSON. Se adoptó el
/// modelo de Android como canónico: más simple, y no exige que el
/// protocolo intercambie identidad Ed25519 además de la clave efímera
/// (que es todo lo que hoy viaja por el chat).
public struct ChatMessage: Equatable {
    public let fromVictim: Bool
    public let text: String
    public let sentAtEpochSeconds: UInt32

    public init(fromVictim: Bool, text: String, sentAtEpochSeconds: UInt32) {
        self.fromVictim = fromVictim
        self.text = text
        self.sentAtEpochSeconds = sentAtEpochSeconds
    }
}

/// Codificación de una lista de `ChatMessage` a una sola cadena y de vuelta
/// - mismo formato exacto que Android (`ChatMessageWireFormat`, #63):
/// registros separados por salto de línea, campos por `|`, texto en Base64
/// dentro de su campo (el único de largo variable que podría contener el
/// separador si el usuario escribe "|" o un salto de línea).
public enum ChatMessageWireFormat {
    private static let fieldSeparator = "|"
    private static let recordSeparator = "\n"

    public static func encode(_ messages: [ChatMessage]) -> String {
        messages.map(encodeOne).joined(separator: recordSeparator)
    }

    public static func decode(_ raw: String) -> [ChatMessage] {
        guard !raw.isEmpty else { return [] }
        return raw.components(separatedBy: recordSeparator).compactMap(decodeOne)
    }

    private static func encodeOne(_ message: ChatMessage) -> String {
        [
            message.fromVictim ? "1" : "0",
            String(message.sentAtEpochSeconds),
            Data(message.text.utf8).base64EncodedString()
        ].joined(separator: fieldSeparator)
    }

    private static func decodeOne(_ raw: String) -> ChatMessage? {
        let parts = raw.components(separatedBy: fieldSeparator)
        guard parts.count == 3,
              let sentAt = UInt32(parts[1]),
              let textData = Data(base64Encoded: parts[2]),
              let text = String(data: textData, encoding: .utf8)
        else { return nil }
        return ChatMessage(fromVictim: parts[0] == "1", text: text, sentAtEpochSeconds: sentAt)
    }
}
