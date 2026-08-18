import Foundation

/// Fragmenta/reensambla `pubkey Ed25519 (32B) || firma Ed25519 (64B)` (96
/// bytes) en/desde los 7 `FragmentoFirmaPacket` de Caso A —
/// `spec/packet-format.md`, sección `FRAGMENTO_FIRMA`. Puro: no decide qué
/// hacer con un payload reensamblado (partirlo en pubkey/firma y verificar
/// es responsabilidad de quien llama, p. ej. un ensamblador con estado en
/// `BeaconRadio`).
public enum SignatureFragmenter {
    /// Corta `publicKey || signature` en fragmentos consecutivos de
    /// `FragmentoFirmaPacket.payloadChunkSize` bytes — el último trae menos.
    public static func fragment(publicKey: Data, signature: Data, deviceIdHash: Data, ttl: UInt8) -> [FragmentoFirmaPacket] {
        precondition(publicKey.count == CaseASignature.publicKeyLength, "publicKey debe medir \(CaseASignature.publicKeyLength) bytes")
        precondition(signature.count == CaseASignature.signatureLength, "signature debe medir \(CaseASignature.signatureLength) bytes")
        let payload = publicKey + signature
        let chunkSize = FragmentoFirmaPacket.payloadChunkSize
        let count = UInt8((payload.count + chunkSize - 1) / chunkSize)

        var fragments: [FragmentoFirmaPacket] = []
        var offset = payload.startIndex
        var index: UInt8 = 0
        while offset < payload.endIndex {
            let end = payload.index(offset, offsetBy: chunkSize, limitedBy: payload.endIndex) ?? payload.endIndex
            let chunk = Data(payload[offset..<end])
            fragments.append(FragmentoFirmaPacket(deviceIdHash: deviceIdHash, ttl: ttl, fragmentIndex: index, fragmentCount: count, chunk: chunk))
            offset = end
            index += 1
        }
        return fragments
    }

    /// Reensambla el payload completo (96 bytes) a partir de fragmentos del
    /// mismo `device_id_hash` — el orden de `fragments` no importa.
    /// Devuelve `nil` si faltan fragmentos, si declaran conteos distintos
    /// entre sí, o si dos fragmentos con el mismo índice traen contenido
    /// distinto (corrupción/manipulación).
    public static func reassemble(_ fragments: [FragmentoFirmaPacket]) -> Data? {
        guard let first = fragments.first else { return nil }
        let deviceIdHash = first.deviceIdHash
        let count = first.fragmentCount
        guard fragments.allSatisfy({ $0.deviceIdHash == deviceIdHash && $0.fragmentCount == count }) else { return nil }

        var chunkByIndex: [UInt8: Data] = [:]
        for fragment in fragments {
            if let existing = chunkByIndex[fragment.fragmentIndex], existing != fragment.chunk {
                return nil
            }
            chunkByIndex[fragment.fragmentIndex] = fragment.chunk
        }
        guard chunkByIndex.count == Int(count) else { return nil }

        var payload = Data()
        for index: UInt8 in 0..<count {
            guard let chunk = chunkByIndex[index] else { return nil }
            payload.append(chunk)
        }
        return payload
    }

    /// Inversa de la concatenación usada en `fragment`: separa un payload
    /// reensamblado (96 bytes) en `pubkey`/`firma`. `nil` si no mide el
    /// tamaño esperado.
    public static func split(_ payload: Data) -> (publicKey: Data, signature: Data)? {
        guard payload.count == FragmentoFirmaPacket.totalPayloadSize else { return nil }
        let publicKey = Data(payload.prefix(CaseASignature.publicKeyLength))
        let signature = Data(payload.suffix(CaseASignature.signatureLength))
        return (publicKey, signature)
    }
}
