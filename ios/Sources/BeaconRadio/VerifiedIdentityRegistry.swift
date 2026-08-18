import Foundation

/// Guarda qué `device_id_hash` de Caso A ya se verificaron localmente en
/// este teléfono (ensamblado+verificación de fragmentos `FRAGMENTO_FIRMA`,
/// ver `SignatureFragmentAssembler`, #44) — "lo que este teléfono sabe",
/// para subir al backend de agregación al entrar a `GATEWAY_ACTIVO`
/// (ticket #52). Mismo molde que `MeshStateRegistry`: solo escucha, no
/// verifica ni gatea nada por sí mismo.
public final class VerifiedIdentityRegistry {
    /// Se dispara solo cuando `record` acepta un `device_id_hash` nuevo —
    /// no en cada llamada.
    public var onIdentityRecorded: ((Data) -> Void)?

    private var deviceIdHashes: Set<Data> = []

    public init() {}

    @discardableResult
    public func record(_ deviceIdHash: Data) -> Bool {
        guard deviceIdHashes.insert(deviceIdHash).inserted else { return false }
        onIdentityRecorded?(deviceIdHash)
        return true
    }

    public func allDeviceIdHashes() -> [Data] { Array(deviceIdHashes) }
}
