import Foundation

/// Formato de 26 bytes definido en `spec/packet-format.md`. Encode/decode aún no
/// implementado — ver el issue de Fase 1 para el contrato exacto y los vectores
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
    /// TODO(Fase 1): encode a 26 bytes little-endian. Ver spec/packet-format.md.
    public static func encode(_ packet: BeaconPacket) -> Data {
        fatalError("BeaconPacketCodec.encode no implementado — ver issue de Fase 1")
    }

    /// TODO(Fase 1): decode desde 26 bytes little-endian. Ver spec/packet-format.md.
    public static func decode(_ data: Data) -> BeaconPacket? {
        fatalError("BeaconPacketCodec.decode no implementado — ver issue de Fase 1")
    }
}
