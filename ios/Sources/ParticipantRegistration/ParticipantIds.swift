import Foundation

/// Misma convención de IDs que `backend/lib/ids.mjs` (`participantDocId`):
/// el ID de documento es el propio `device_id_hash` en hex — así las reglas
/// de Firestore (`matchesParticipantId`) pueden comparar el docId contra el
/// campo sin ambigüedad de formato.
public enum ParticipantIds {
    public static func deviceIdHashHex(_ deviceIdHash: Data) -> String {
        deviceIdHash.map { String(format: "%02x", $0) }.joined()
    }

    public static func participantDocId(deviceIdHash: Data) -> String {
        deviceIdHashHex(deviceIdHash)
    }
}
