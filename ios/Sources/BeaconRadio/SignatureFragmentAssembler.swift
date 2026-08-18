import Foundation
import PacketCodec

/// Junta fragmentos `FRAGMENTO_FIRMA` (Caso A) por `device_id_hash` y
/// verifica localmente la identidad en cuanto hay suficientes —
/// `spec/packet-format.md`, sección `FRAGMENTO_FIRMA`. Solo escucha: nunca
/// gatea ni descarta retransmisión de ningún fragmento (el TTL/dedup de
/// `RelayQueue`/`DedupCache` sigue siendo el único criterio de descarte,
/// AC de #44) — mismo principio de "solo alimenta un side-channel" que
/// `MeshStateRegistry`.
///
/// Capacidad + TTL para los conjuntos parciales sin completar, mismo motivo
/// y mismo patrón que `DedupCache`: la propia decisión 18 del spec ya
/// reconoce que un emisor arbitrario puede mandar fragmentos con
/// `device_id_hash` distintos cada vez — sin este límite, ese tráfico
/// podría crecer la memoria del acumulador sin cota.
public final class SignatureFragmentAssembler {
    /// Se dispara una sola vez por `device_id_hash`, la primera vez que se
    /// junta un conjunto completo de fragmentos cuya firma reensamblada
    /// verifica contra su propia pubkey (autocertificado, ver
    /// `CaseASignature`).
    public var onIdentityVerified: ((_ deviceIdHash: Data, _ publicKey: Data) -> Void)?

    private var fragmentsByDevice: [Data: [UInt8: FragmentoFirmaPacket]] = [:]
    private var insertedAt: [Data: Date] = [:]
    private var recencyOrder: [Data] = [] // más antiguo al frente, más reciente al final
    private var verifiedDevices: Set<Data> = []

    private let capacity: Int
    private let ttl: TimeInterval
    private let now: () -> Date

    public init(capacity: Int = 500, ttl: TimeInterval = 30 * 60, now: @escaping () -> Date = Date.init) {
        self.capacity = capacity
        self.ttl = ttl
        self.now = now
    }

    /// Registra un fragmento recibido. Devuelve `true` solo la vez que este
    /// fragmento completa el conjunto y la verificación de la identidad
    /// pasa por primera vez.
    @discardableResult
    public func receive(_ fragment: FragmentoFirmaPacket) -> Bool {
        guard !verifiedDevices.contains(fragment.deviceIdHash) else { return false }
        purgeExpired()
        accumulate(fragment)
        return tryVerify(deviceIdHash: fragment.deviceIdHash)
    }

    public func isVerified(_ deviceIdHash: Data) -> Bool {
        verifiedDevices.contains(deviceIdHash)
    }

    private func accumulate(_ fragment: FragmentoFirmaPacket) {
        var byIndex = fragmentsByDevice[fragment.deviceIdHash] ?? [:]
        if let existingCount = byIndex.values.first?.fragmentCount, existingCount != fragment.fragmentCount {
            // Conteo inconsistente entre fragmentos del mismo dispositivo
            // (corrupción o identidad reemitida) — reiniciar acumulación
            // con el conteo más reciente en vez de mezclar dos series.
            byIndex = [:]
        }
        byIndex[fragment.fragmentIndex] = fragment
        fragmentsByDevice[fragment.deviceIdHash] = byIndex
        touch(fragment.deviceIdHash)
        evictIfNeeded()
    }

    private func tryVerify(deviceIdHash: Data) -> Bool {
        guard let byIndex = fragmentsByDevice[deviceIdHash],
              let first = byIndex.values.first,
              byIndex.count == Int(first.fragmentCount),
              let payload = SignatureFragmenter.reassemble(Array(byIndex.values)),
              let (publicKey, signature) = SignatureFragmenter.split(payload),
              CaseASignature.verify(publicKey: publicKey, signature: signature)
        else { return false }

        verifiedDevices.insert(deviceIdHash)
        forget(deviceIdHash)
        onIdentityVerified?(deviceIdHash, publicKey)
        return true
    }

    private func touch(_ deviceIdHash: Data) {
        insertedAt[deviceIdHash] = now()
        if let index = recencyOrder.firstIndex(of: deviceIdHash) {
            recencyOrder.remove(at: index)
        }
        recencyOrder.append(deviceIdHash)
    }

    private func forget(_ deviceIdHash: Data) {
        fragmentsByDevice.removeValue(forKey: deviceIdHash)
        insertedAt.removeValue(forKey: deviceIdHash)
        recencyOrder.removeAll { $0 == deviceIdHash }
    }

    private func evictIfNeeded() {
        while recencyOrder.count > capacity {
            forget(recencyOrder[0])
        }
    }

    private func purgeExpired() {
        let currentTime = now()
        for deviceIdHash in recencyOrder {
            guard let insertedTime = insertedAt[deviceIdHash] else { continue }
            if currentTime.timeIntervalSince(insertedTime) >= ttl {
                forget(deviceIdHash)
            }
        }
    }
}
