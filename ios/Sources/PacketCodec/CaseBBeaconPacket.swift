import Foundation

/// Layout de 27 bytes de Caso B (beacon autenticado, `Versión=0x02`)
/// definido en `spec/packet-format.md`. Reusa `BeaconPacket.MessageType`/
/// `BeaconPacket.Status` (mismos enums, empaquetados en un solo byte
/// `TipoEstado` en este layout) — ver los vectores de prueba en
/// `spec/test-vectors.json`, clave `case_b`.
public struct CaseBBeaconPacket: Equatable {
    public static let magic: UInt8 = 0xE7
    public static let version: UInt8 = 0x02
    public static let packetSize = 27

    public var messageType: BeaconPacket.MessageType
    public var deviceIdHash: Data // 6 bytes
    public var status: BeaconPacket.Status
    public var latitudeE7: Int32
    public var longitudeE7: Int32
    public var timestamp: UInt32
    public var ttl: UInt8
    public var mac: Data // 4 bytes
    public var sequence: UInt8

    public init(
        messageType: BeaconPacket.MessageType,
        deviceIdHash: Data,
        status: BeaconPacket.Status,
        latitudeE7: Int32,
        longitudeE7: Int32,
        timestamp: UInt32,
        ttl: UInt8,
        mac: Data,
        sequence: UInt8
    ) {
        precondition(deviceIdHash.count == 6, "deviceIdHash debe medir 6 bytes")
        precondition(mac.count == 4, "mac debe medir 4 bytes")
        self.messageType = messageType
        self.deviceIdHash = deviceIdHash
        self.status = status
        self.latitudeE7 = latitudeE7
        self.longitudeE7 = longitudeE7
        self.timestamp = timestamp
        self.ttl = ttl
        self.mac = mac
        self.sequence = sequence
    }
}

public enum CaseBBeaconPacketCodec {
    public static func encode(_ packet: CaseBBeaconPacket) -> Data {
        var data = Data(capacity: CaseBBeaconPacket.packetSize)
        data.append(CaseBBeaconPacket.magic)
        data.append(CaseBBeaconPacket.version)
        data.append(tipoEstado(messageType: packet.messageType, status: packet.status))
        data.append(packet.deviceIdHash)
        data.appendLE(UInt32(bitPattern: packet.latitudeE7))
        data.appendLE(UInt32(bitPattern: packet.longitudeE7))
        data.appendLE(packet.timestamp)
        data.append(packet.ttl)
        data.append(packet.mac)
        data.append(packet.sequence)
        return data
    }

    /// Offsets del layout de 27 bytes documentado en `spec/packet-format.md`.
    private enum Offset {
        static let magic = 0
        static let version = 1
        static let tipoEstado = 2
        static let deviceIdHash = 3 // 6 bytes: 3..<9
        static let latitude = 9
        static let longitude = 13
        static let timestamp = 17
        static let ttl = 21
        static let mac = 22 // 4 bytes: 22..<26
        static let sequence = 26
    }

    public static func decode(_ data: Data) -> CaseBBeaconPacket? {
        guard data.count == CaseBBeaconPacket.packetSize else { return nil }
        let base = data.startIndex
        guard data[base + Offset.magic] == CaseBBeaconPacket.magic,
              data[base + Offset.version] == CaseBBeaconPacket.version else { return nil }
        guard let (messageType, status) = decodeTipoEstado(data[base + Offset.tipoEstado]) else { return nil }
        let deviceIdHash = Data(data[(base + Offset.deviceIdHash)..<(base + Offset.latitude)])
        let latitudeE7 = Int32(bitPattern: data.leUInt32(at: Offset.latitude))
        let longitudeE7 = Int32(bitPattern: data.leUInt32(at: Offset.longitude))
        let timestamp = data.leUInt32(at: Offset.timestamp)
        let ttl = data[base + Offset.ttl]
        let mac = Data(data[(base + Offset.mac)..<(base + Offset.sequence)])
        let sequence = data[base + Offset.sequence]
        return CaseBBeaconPacket(
            messageType: messageType,
            deviceIdHash: deviceIdHash,
            status: status,
            latitudeE7: latitudeE7,
            longitudeE7: longitudeE7,
            timestamp: timestamp,
            ttl: ttl,
            mac: mac,
            sequence: sequence
        )
    }

    /// Nibble alto = `Tipo de mensaje`, nibble bajo = `Estado` — decisión 16
    /// de `spec/packet-format.md`.
    static func tipoEstado(messageType: BeaconPacket.MessageType, status: BeaconPacket.Status) -> UInt8 {
        (messageType.rawValue << 4) | status.rawValue
    }

    private static func decodeTipoEstado(_ byte: UInt8) -> (BeaconPacket.MessageType, BeaconPacket.Status)? {
        guard let messageType = BeaconPacket.MessageType(rawValue: byte >> 4),
              let status = BeaconPacket.Status(rawValue: byte & 0x0F) else { return nil }
        return (messageType, status)
    }
}
