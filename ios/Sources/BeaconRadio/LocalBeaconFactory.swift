import Foundation
import PacketCodec

/// Genera un nonce aleatorio por beacon (`spec/packet-format.md`, campo
/// Nonce) — inyectable para que `LocalBeaconFactory` sea testeable sin
/// depender de aleatoriedad real.
public protocol NonceGenerating {
    func nextNonce() -> UInt16
}

public struct RandomNonceGenerator: NonceGenerating {
    public init() {}
    public func nextNonce() -> UInt16 { UInt16.random(in: 0...UInt16.max) }
}

/// Construye el `BeaconPacket` que este nodo emite a partir del estado de la
/// Máquina de estados A (#4/#5).
public enum LocalBeaconFactory {
    /// TTL inicial de un beacon recién emitido por este nodo. Se resta 1 por
    /// retransmisión — esa lógica pertenece a la ticket de relay (#8/#9), no
    /// a esta.
    public static let initialTtl: UInt8 = 16

    /// Limitación conocida: sin captura de GPS todavía — lat/long quedan en
    /// 0 hasta que un ticket futuro integre ubicación real.
    public static func makeBeacon(
        deviceIdHash: Data,
        status: BeaconPacket.Status,
        sequence: UInt8,
        now: Date,
        nonceGenerator: NonceGenerating
    ) -> BeaconPacket {
        BeaconPacket(
            messageType: .beacon,
            deviceIdHash: deviceIdHash,
            status: status,
            latitudeE7: 0,
            longitudeE7: 0,
            timestamp: UInt32(now.timeIntervalSince1970),
            ttl: initialTtl,
            nonce: nonceGenerator.nextNonce(),
            sequence: sequence
        )
    }
}
