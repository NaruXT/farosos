import Foundation

/// Formato de 26 bytes definido en `spec/packet-format.md`. Ver los vectores
/// de prueba en `spec/test-vectors.json`.
public struct BeaconPacket: Equatable {
    public static let magic: UInt8 = 0xE7
    public static let version: UInt8 = 0x01
    public static let packetSize = 26

    public enum MessageType: UInt8 {
        case beacon = 0
        case gatewayAnnounce = 1
        case ackReceived = 2
    }

    public enum Status: UInt8 {
        case sinConfirmar = 0
        case ok = 1
        case ayuda = 2
        case silencioTimeout = 3
        case gatewayDisponible = 4
    }

    public var messageType: MessageType
    public var deviceIdHash: Data // 6 bytes
    public var status: Status
    public var latitudeE7: Int32
    public var longitudeE7: Int32
    public var timestamp: UInt32
    public var ttl: UInt8
    public var nonce: UInt16
    public var sequence: UInt8

    public init(
        messageType: MessageType,
        deviceIdHash: Data,
        status: Status,
        latitudeE7: Int32,
        longitudeE7: Int32,
        timestamp: UInt32,
        ttl: UInt8,
        nonce: UInt16,
        sequence: UInt8
    ) {
        precondition(deviceIdHash.count == 6, "deviceIdHash debe medir 6 bytes")
        self.messageType = messageType
        self.deviceIdHash = deviceIdHash
        self.status = status
        self.latitudeE7 = latitudeE7
        self.longitudeE7 = longitudeE7
        self.timestamp = timestamp
        self.ttl = ttl
        self.nonce = nonce
        self.sequence = sequence
    }
}

public enum BeaconPacketCodec {
    public static func encode(_ packet: BeaconPacket) -> Data {
        var data = Data(capacity: BeaconPacket.packetSize)
        data.append(BeaconPacket.magic)
        data.append(BeaconPacket.version)
        data.append(packet.messageType.rawValue)
        data.append(packet.deviceIdHash)
        data.append(packet.status.rawValue)
        data.appendLE(UInt32(bitPattern: packet.latitudeE7))
        data.appendLE(UInt32(bitPattern: packet.longitudeE7))
        data.appendLE(packet.timestamp)
        data.append(packet.ttl)
        data.appendLE(packet.nonce)
        data.append(packet.sequence)
        return data
    }

    /// Offsets del layout de 26 bytes documentado en `spec/packet-format.md`.
    private enum Offset {
        static let magic = 0
        static let version = 1
        static let messageType = 2
        static let deviceIdHash = 3 // 6 bytes: 3..<9
        static let status = 9
        static let latitude = 10
        static let longitude = 14
        static let timestamp = 18
        static let ttl = 22
        static let nonce = 23
        static let sequence = 25
    }

    public static func decode(_ data: Data) -> BeaconPacket? {
        guard data.count == BeaconPacket.packetSize else { return nil }
        let base = data.startIndex
        guard data[base + Offset.magic] == BeaconPacket.magic,
              data[base + Offset.version] == BeaconPacket.version else { return nil }
        guard let messageType = BeaconPacket.MessageType(rawValue: data[base + Offset.messageType]) else { return nil }
        let deviceIdHash = Data(data[(base + Offset.deviceIdHash)..<(base + Offset.status)])
        guard let status = BeaconPacket.Status(rawValue: data[base + Offset.status]) else { return nil }
        let latitudeE7 = Int32(bitPattern: data.leUInt32(at: Offset.latitude))
        let longitudeE7 = Int32(bitPattern: data.leUInt32(at: Offset.longitude))
        let timestamp = data.leUInt32(at: Offset.timestamp)
        let ttl = data[base + Offset.ttl]
        let nonce = data.leUInt16(at: Offset.nonce)
        let sequence = data[base + Offset.sequence]
        return BeaconPacket(
            messageType: messageType,
            deviceIdHash: deviceIdHash,
            status: status,
            latitudeE7: latitudeE7,
            longitudeE7: longitudeE7,
            timestamp: timestamp,
            ttl: ttl,
            nonce: nonce,
            sequence: sequence
        )
    }
}

/// Lectura/escritura little-endian manual (en vez de `loadUnaligned`) para no
/// requerir alineación de memoria ni una versión mínima de iOS más nueva que
/// la que ya declara `Package.swift`. `internal` (no `private`) porque
/// `CaseBBeaconPacket.swift` (mismo módulo) también las necesita.
extension Data {
    mutating func appendLE(_ value: UInt16) {
        append(UInt8(value & 0xFF))
        append(UInt8((value >> 8) & 0xFF))
    }

    mutating func appendLE(_ value: UInt32) {
        append(UInt8(value & 0xFF))
        append(UInt8((value >> 8) & 0xFF))
        append(UInt8((value >> 16) & 0xFF))
        append(UInt8((value >> 24) & 0xFF))
    }

    func leUInt16(at offset: Int) -> UInt16 {
        let i = startIndex + offset
        return UInt16(self[i]) | (UInt16(self[i + 1]) << 8)
    }

    func leUInt32(at offset: Int) -> UInt32 {
        let i = startIndex + offset
        return UInt32(self[i])
            | (UInt32(self[i + 1]) << 8)
            | (UInt32(self[i + 2]) << 16)
            | (UInt32(self[i + 3]) << 24)
    }
}
