import Foundation

/// Marca de "resuelto" (#55) sobre el caso de otro participante — captura la
/// secuencia de la víctima que este teléfono conocía al momento de marcar
/// (no la más reciente que exista al subir, para no resolver un estado que
/// el resolutor nunca vio) y su propia ubicación. Codable porque la capa de
/// app la persiste como JSON en Keychain (`KeychainResolutionStore`) hasta
/// que haya conectividad propia — sin pasar por la malla BLE (ver #55).
public struct ResolvedMark: Equatable, Codable {
    public let victimDeviceIdHash: Data
    public let victimSequence: UInt8
    public let resolverDeviceIdHash: Data
    public let resolverLatitudeE7: Int32
    public let resolverLongitudeE7: Int32
    public let markedAt: UInt32

    public init(
        victimDeviceIdHash: Data,
        victimSequence: UInt8,
        resolverDeviceIdHash: Data,
        resolverLatitudeE7: Int32,
        resolverLongitudeE7: Int32,
        markedAt: UInt32
    ) {
        self.victimDeviceIdHash = victimDeviceIdHash
        self.victimSequence = victimSequence
        self.resolverDeviceIdHash = resolverDeviceIdHash
        self.resolverLatitudeE7 = resolverLatitudeE7
        self.resolverLongitudeE7 = resolverLongitudeE7
        self.markedAt = markedAt
    }
}

/// Marca de "atendiendo" (#55) — a diferencia de `ResolvedMark`, no lleva
/// ubicación ni verificación de proximidad (señal de menor consecuencia) y
/// admite que varios participantes la marquen en paralelo sobre el mismo
/// caso (`atendido_por`, lista en el backend).
public struct AttendingMark: Equatable, Codable {
    public let victimDeviceIdHash: Data
    public let victimSequence: UInt8
    public let resolverDeviceIdHash: Data
    public let markedAt: UInt32

    public init(
        victimDeviceIdHash: Data,
        victimSequence: UInt8,
        resolverDeviceIdHash: Data,
        markedAt: UInt32
    ) {
        self.victimDeviceIdHash = victimDeviceIdHash
        self.victimSequence = victimSequence
        self.resolverDeviceIdHash = resolverDeviceIdHash
        self.markedAt = markedAt
    }
}
