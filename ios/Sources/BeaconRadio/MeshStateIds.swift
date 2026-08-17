import Foundation

/// Misma convención de IDs que `backend/lib/ids.mjs` (`meshStateDocId`): el
/// ID de documento es `{device_id_hash}_{sequence}` (ADR-0002, dedup
/// multi-gateway) — así dos gateways subiendo el mismo (persona, secuencia)
/// escriben siempre el mismo documento.
public enum MeshStateIds {
    public static func deviceIdHashHex(_ deviceIdHash: Data) -> String {
        deviceIdHash.map { String(format: "%02x", $0) }.joined()
    }

    public static func docId(deviceIdHash: Data, sequence: UInt8) -> String {
        "\(deviceIdHashHex(deviceIdHash))_\(sequence)"
    }
}
