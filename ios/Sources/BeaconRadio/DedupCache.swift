import Foundation

/// Caché de deduplicación de beacons vistos, descrita en
/// `spec/packet-format.md` (sección "Deduplicación" + decisión 12). Clave
/// `DeviceIDHash + Nonce`, expiración simple por TTL y desalojo LRU con tope
/// de entradas. El emisor de un beacon se auto-registra aquí al emitir, así
/// que un rebote de su propio paquete se descarta por el mismo camino que
/// cualquier otro duplicado — no hay una ruta de código especial para "es
/// mío".
public final class DedupCache {
    /// `discriminator` distingue paquetes de un mismo dispositivo — `Nonce`
    /// (2 bytes) en el layout legado, `MAC` (4 bytes) en Caso B
    /// (`Versión=0x02`, #39/#42). Los tamaños distintos bastan para que
    /// nunca colisionen entre sí sin necesitar una marca de caso aparte.
    public struct Key: Hashable {
        public let deviceIdHash: Data
        private let discriminator: Data

        public init(deviceIdHash: Data, nonce: UInt16) {
            self.deviceIdHash = deviceIdHash
            self.discriminator = Data([UInt8(nonce & 0xFF), UInt8((nonce >> 8) & 0xFF)])
        }

        public init(deviceIdHash: Data, mac: Data) {
            precondition(mac.count == 4, "mac debe medir 4 bytes")
            self.deviceIdHash = deviceIdHash
            self.discriminator = mac
        }
    }

    private var insertedAt: [Key: Date] = [:]
    private var recencyOrder: [Key] = [] // más antigua al frente, más reciente al final
    private let capacity: Int
    private let ttl: TimeInterval
    private let now: () -> Date

    public init(capacity: Int = 500, ttl: TimeInterval = 30 * 60, now: @escaping () -> Date = Date.init) {
        self.capacity = capacity
        self.ttl = ttl
        self.now = now
    }

    /// Registra `key` si es nueva o si su entrada anterior ya expiró, y
    /// devuelve `true`. Si ya estaba vigente, la refresca (LRU) y devuelve
    /// `false` — es un duplicado.
    ///
    /// La expiración es una ventana deslizante: cada repetición de una
    /// clave vigente extiende su TTL otros ~30 min (decisión de diseño,
    /// no ambigüedad) — así un beacon que se sigue anunciando sin cambios
    /// no vuelve a aparecer como "recibido" solo porque pasó media hora
    /// desde la primera vez que se vio.
    @discardableResult
    public func insertIfAbsent(_ key: Key) -> Bool {
        purgeExpired()
        if insertedAt[key] != nil {
            touch(key)
            return false
        }
        insertedAt[key] = now()
        recencyOrder.append(key)
        evictIfNeeded()
        return true
    }

    private func touch(_ key: Key) {
        insertedAt[key] = now()
        if let index = recencyOrder.firstIndex(of: key) {
            recencyOrder.remove(at: index)
        }
        recencyOrder.append(key)
    }

    private func evictIfNeeded() {
        while recencyOrder.count > capacity {
            let oldest = recencyOrder.removeFirst()
            insertedAt.removeValue(forKey: oldest)
        }
    }

    private func purgeExpired() {
        let currentTime = now()
        recencyOrder.removeAll { key in
            guard let insertedTime = insertedAt[key] else { return true }
            let expired = currentTime.timeIntervalSince(insertedTime) >= ttl
            if expired { insertedAt.removeValue(forKey: key) }
            return expired
        }
    }
}
