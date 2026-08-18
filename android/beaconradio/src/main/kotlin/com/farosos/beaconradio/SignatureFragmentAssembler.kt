package com.farosos.beaconradio

import com.farosos.codec.CaseASignature
import com.farosos.codec.FragmentoFirmaPacket
import com.farosos.codec.SignatureFragmenter

/**
 * Junta fragmentos `FRAGMENTO_FIRMA` (Caso A) por `device_id_hash` y
 * verifica localmente la identidad en cuanto hay suficientes —
 * `spec/packet-format.md`, sección `FRAGMENTO_FIRMA`. Solo escucha: nunca
 * gatea ni descarta retransmisión de ningún fragmento (el TTL/dedup de
 * `RelayQueue`/`DedupCache` sigue siendo el único criterio de descarte, AC
 * de #45) — mismo principio de "solo alimenta un side-channel" que
 * `MeshStateRegistry`.
 *
 * Capacidad + TTL para los conjuntos parciales sin completar, mismo motivo
 * y mismo patrón que `DedupCache`: la propia decisión 18 del spec ya
 * reconoce que un emisor arbitrario puede mandar fragmentos con
 * `device_id_hash` distintos cada vez — sin este límite, ese tráfico
 * podría crecer la memoria del acumulador sin cota (hallazgo real del
 * code-review de la contraparte iOS, #44, aplicado aquí desde el arranque).
 */
class SignatureFragmentAssembler(
    private val capacity: Int = 500,
    private val ttlMillis: Long = 30 * 60 * 1000L,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    /**
     * Se dispara una sola vez por `device_id_hash`, la primera vez que se
     * junta un conjunto completo de fragmentos cuya firma reensamblada
     * verifica contra su propia pubkey (autocertificado, ver
     * `CaseASignature`).
     */
    var onIdentityVerified: ((deviceIdHash: ByteArray, publicKey: ByteArray) -> Unit)? = null

    private class DeviceKey(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is DeviceKey && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    private val fragmentsByDevice = LinkedHashMap<DeviceKey, MutableMap<Int, FragmentoFirmaPacket>>() // orden de inserción/toque = orden LRU
    private val insertedAt = HashMap<DeviceKey, Long>()
    private val verifiedDevices = HashSet<DeviceKey>()

    /**
     * Registra un fragmento recibido. Devuelve `true` solo la vez que este
     * fragmento completa el conjunto y la verificación de la identidad pasa
     * por primera vez.
     */
    fun receive(fragment: FragmentoFirmaPacket): Boolean {
        val key = DeviceKey(fragment.deviceIdHash)
        if (key in verifiedDevices) return false
        purgeExpired()
        accumulate(key, fragment)
        return tryVerify(key, fragment.deviceIdHash)
    }

    fun isVerified(deviceIdHash: ByteArray): Boolean = DeviceKey(deviceIdHash) in verifiedDevices

    private fun accumulate(key: DeviceKey, fragment: FragmentoFirmaPacket) {
        var byIndex = fragmentsByDevice[key]
        if (byIndex != null && byIndex.values.firstOrNull()?.fragmentCount != fragment.fragmentCount) {
            // Conteo inconsistente entre fragmentos del mismo dispositivo
            // (corrupción o identidad reemitida) — reiniciar acumulación
            // con el conteo más reciente en vez de mezclar dos series.
            byIndex = null
        }
        val target = byIndex ?: mutableMapOf()
        target[fragment.fragmentIndex] = fragment
        fragmentsByDevice[key] = target
        touch(key)
        evictIfNeeded()
    }

    private fun tryVerify(key: DeviceKey, deviceIdHash: ByteArray): Boolean {
        val byIndex = fragmentsByDevice[key] ?: return false
        val first = byIndex.values.firstOrNull() ?: return false
        if (byIndex.size != first.fragmentCount) return false
        val payload = SignatureFragmenter.reassemble(byIndex.values.toList()) ?: return false
        val (publicKey, signature) = SignatureFragmenter.split(payload) ?: return false
        if (!CaseASignature.verify(publicKey, signature)) return false

        verifiedDevices.add(key)
        forget(key)
        onIdentityVerified?.invoke(deviceIdHash, publicKey)
        return true
    }

    private fun touch(key: DeviceKey) {
        // insertedAt es un HashMap plano — solo importa el valor (última vez
        // tocada), no su orden, así que un put alcanza. fragmentsByDevice sí
        // es un LinkedHashMap cuyo orden de entradas define el desalojo LRU
        // (evictIfNeeded) — remove+reinsertar mueve la clave al final
        // (más reciente).
        insertedAt[key] = nowMillis()
        val entry = fragmentsByDevice.remove(key)
        if (entry != null) fragmentsByDevice[key] = entry
    }

    private fun forget(key: DeviceKey) {
        fragmentsByDevice.remove(key)
        insertedAt.remove(key)
    }

    private fun evictIfNeeded() {
        val iterator = fragmentsByDevice.keys.iterator()
        while (fragmentsByDevice.size > capacity && iterator.hasNext()) {
            val oldest = iterator.next()
            iterator.remove()
            insertedAt.remove(oldest)
        }
    }

    private fun purgeExpired() {
        val currentTime = nowMillis()
        val expired = insertedAt.entries.filter { (_, insertedTime) -> currentTime - insertedTime >= ttlMillis }.map { it.key }
        for (key in expired) forget(key)
    }
}
