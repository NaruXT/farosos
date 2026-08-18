import Foundation

/// Fragmento de `FRAGMENTO_FIRMA` (Caso A, layout legado `Versión=0x01`,
/// `Tipo=3`) definido en `spec/packet-format.md`. Un dispositivo que nunca
/// tuvo conectividad fragmenta `pubkey Ed25519 (32B) || firma Ed25519 (64B)`
/// (96 bytes) en 7 fragmentos de 15 bytes — ver `SignatureFragmenter` para
/// fragmentar/reensamblar el payload completo. Vectores de prueba en
/// `spec/test-vectors.json`, clave `fragmento_firma`.
public struct FragmentoFirmaPacket: Equatable {
    public static let magic: UInt8 = 0xE7
    public static let version: UInt8 = 0x01
    public static let messageType: UInt8 = 3
    public static let packetSize = 26
    public static let payloadChunkSize = 15
    /// `pubkey Ed25519 (32B) + firma Ed25519 (64B)` — tamaño fijo por
    /// construcción (Ed25519 no cambia de tamaño), usado para saber cuántos
    /// bytes reales trae el último fragmento sin necesitar un campo de
    /// longitud aparte (ver `FragmentoFirmaPacketCodec.decode`).
    public static let totalPayloadSize = 96

    public var deviceIdHash: Data // 6 bytes
    public var ttl: UInt8
    public var fragmentIndex: UInt8 // 0-6
    public var fragmentCount: UInt8 // siempre 7 hoy (96 bytes / 15 por fragmento)
    /// Bytes REALES del fragmento, sin el relleno de ceros que sí lleva el
    /// paquete en el aire — 15 bytes, salvo el último fragmento (6 bytes).
    public var chunk: Data

    public init(deviceIdHash: Data, ttl: UInt8, fragmentIndex: UInt8, fragmentCount: UInt8, chunk: Data) {
        precondition(deviceIdHash.count == 6, "deviceIdHash debe medir 6 bytes")
        precondition(chunk.count <= Self.payloadChunkSize, "chunk no puede superar \(Self.payloadChunkSize) bytes")
        precondition(fragmentIndex <= 0x0F && fragmentCount <= 0x0F, "índice y conteo deben caber en un nibble (0-15)")
        self.deviceIdHash = deviceIdHash
        self.ttl = ttl
        self.fragmentIndex = fragmentIndex
        self.fragmentCount = fragmentCount
        self.chunk = chunk
    }
}

public enum FragmentoFirmaPacketCodec {
    public static func encode(_ packet: FragmentoFirmaPacket) -> Data {
        var data = Data(capacity: FragmentoFirmaPacket.packetSize)
        data.append(FragmentoFirmaPacket.magic)
        data.append(FragmentoFirmaPacket.version)
        data.append(FragmentoFirmaPacket.messageType)
        data.append(packet.deviceIdHash)
        data.append(packet.ttl)
        data.append(fragHeader(index: packet.fragmentIndex, count: packet.fragmentCount))
        data.append(packet.chunk)
        data.append(Data(repeating: 0, count: FragmentoFirmaPacket.payloadChunkSize - packet.chunk.count))
        return data
    }

    /// Offsets del layout de 26 bytes documentado en `spec/packet-format.md`.
    private enum Offset {
        static let magic = 0
        static let version = 1
        static let messageType = 2
        static let deviceIdHash = 3 // 6 bytes: 3..<9
        static let ttl = 9
        static let fragHeader = 10
        static let payload = 11 // 15 bytes: 11..<26
    }

    public static func decode(_ data: Data) -> FragmentoFirmaPacket? {
        guard data.count == FragmentoFirmaPacket.packetSize else { return nil }
        let base = data.startIndex
        guard data[base + Offset.magic] == FragmentoFirmaPacket.magic,
              data[base + Offset.version] == FragmentoFirmaPacket.version,
              data[base + Offset.messageType] == FragmentoFirmaPacket.messageType else { return nil }
        let deviceIdHash = Data(data[(base + Offset.deviceIdHash)..<(base + Offset.ttl)])
        let ttl = data[base + Offset.ttl]
        let (index, count) = decodeFragHeader(data[base + Offset.fragHeader])
        guard count > 0, index < count else { return nil }
        let realLength = realChunkLength(index: index, count: count)
        let chunk = Data(data[(base + Offset.payload)..<(base + Offset.payload + realLength)])
        return FragmentoFirmaPacket(deviceIdHash: deviceIdHash, ttl: ttl, fragmentIndex: index, fragmentCount: count, chunk: chunk)
    }

    /// Nibble alto = índice de fragmento, nibble bajo = conteo total.
    static func fragHeader(index: UInt8, count: UInt8) -> UInt8 {
        (index << 4) | count
    }

    private static func decodeFragHeader(_ byte: UInt8) -> (UInt8, UInt8) {
        (byte >> 4, byte & 0x0F)
    }

    /// El último fragmento (`index == count - 1`) trae menos de
    /// `payloadChunkSize` bytes reales — el resto del payload de 15 bytes en
    /// el paquete es relleno de ceros, recortado aquí sin necesitar un
    /// campo de longitud aparte.
    private static func realChunkLength(index: UInt8, count: UInt8) -> Int {
        guard index == count - 1 else { return FragmentoFirmaPacket.payloadChunkSize }
        let consumedByEarlierFragments = Int(count - 1) * FragmentoFirmaPacket.payloadChunkSize
        return FragmentoFirmaPacket.totalPayloadSize - consumedByEarlierFragments
    }
}
