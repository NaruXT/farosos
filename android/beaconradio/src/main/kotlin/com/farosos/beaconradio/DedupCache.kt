package com.farosos.beaconradio

/**
 * Caché de deduplicación de beacons vistos, descrita en
 * `spec/packet-format.md` (sección "Deduplicación" + decisión 12). Clave
 * `DeviceIDHash + Nonce`, expiración simple por TTL y desalojo LRU con tope
 * de entradas. El emisor de un beacon se auto-registra aquí al emitir, así
 * que un rebote de su propio paquete se descarta por el mismo camino que
 * cualquier otro duplicado — no hay una ruta de código especial para "es
 * mío".
 */
class DedupCache(
    private val capacity: Int = 500,
    private val ttlMillis: Long = 30 * 60 * 1000L,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    /**
     * [discriminator] distingue paquetes de un mismo dispositivo — `Nonce`
     * (2 bytes) en el layout legado, `MAC` (4 bytes) en Caso B
     * (`Versión=0x02`, #39/#43). Los tamaños distintos bastan para que
     * nunca colisionen entre sí sin necesitar una marca de caso aparte.
     */
    class Key private constructor(val deviceIdHash: ByteArray, private val discriminator: ByteArray) {
        constructor(deviceIdHash: ByteArray, nonce: Int) : this(
            deviceIdHash,
            byteArrayOf((nonce and 0xFF).toByte(), ((nonce shr 8) and 0xFF).toByte())
        )

        override fun equals(other: Any?): Boolean =
            other is Key && deviceIdHash.contentEquals(other.deviceIdHash) && discriminator.contentEquals(other.discriminator)

        override fun hashCode(): Int = 31 * deviceIdHash.contentHashCode() + discriminator.contentHashCode()

        companion object {
            // No es un constructor secundario `(ByteArray, ByteArray)` porque
            // choca en JVM con el constructor privado primario de la misma
            // forma — Kotlin no distingue overloads solo por nombre de
            // parámetro cuando el tipo erasure es idéntico.
            fun forMac(deviceIdHash: ByteArray, mac: ByteArray): Key {
                require(mac.size == 4) { "mac debe medir 4 bytes" }
                return Key(deviceIdHash, mac)
            }
        }
    }

    private val insertedAt = LinkedHashMap<Key, Long>() // orden de inserción/toque = orden LRU

    /**
     * Registra [key] si es nueva o si su entrada anterior ya expiró, y
     * devuelve `true`. Si ya estaba vigente, la refresca (LRU) y devuelve
     * `false` — es un duplicado.
     *
     * La expiración es una ventana deslizante: cada repetición de una clave
     * vigente extiende su TTL otros ~30 min (decisión de diseño, no
     * ambigüedad) — así un beacon que se sigue anunciando sin cambios no
     * vuelve a aparecer como "recibido" solo porque pasó media hora desde la
     * primera vez que se vio.
     */
    @Synchronized
    fun insertIfAbsent(key: Key): Boolean {
        purgeExpired()
        if (insertedAt.containsKey(key)) {
            touch(key)
            return false
        }
        insertedAt[key] = nowMillis()
        evictIfNeeded()
        return true
    }

    private fun touch(key: Key) {
        insertedAt.remove(key)
        insertedAt[key] = nowMillis()
    }

    private fun evictIfNeeded() {
        val iterator = insertedAt.keys.iterator()
        while (insertedAt.size > capacity && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    private fun purgeExpired() {
        val currentTime = nowMillis()
        insertedAt.entries.removeAll { (_, insertedTime) -> currentTime - insertedTime >= ttlMillis }
    }
}
