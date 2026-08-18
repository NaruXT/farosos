package com.farosos.beaconradio

/**
 * Guarda qué `device_id_hash` de Caso A ya se verificaron localmente en
 * este teléfono (ensamblado+verificación de fragmentos `FRAGMENTO_FIRMA`,
 * ver `SignatureFragmentAssembler`, #45) — "lo que este teléfono sabe",
 * para subir al backend de agregación al entrar a `GATEWAY_ACTIVO`
 * (ticket #53). Mismo molde que `MeshStateRegistry` (mapa por hex string,
 * no un wrapper de `ByteArray`): solo escucha, no verifica ni gatea nada
 * por sí mismo.
 */
class VerifiedIdentityRegistry {
    /** Se dispara solo cuando `record` acepta un `device_id_hash` nuevo — no en cada llamada. */
    var onIdentityRecorded: ((ByteArray) -> Unit)? = null

    private val deviceIdHashes = mutableMapOf<String, ByteArray>()

    fun record(deviceIdHash: ByteArray): Boolean {
        val key = MeshStateIds.deviceIdHashHex(deviceIdHash)
        if (deviceIdHashes.containsKey(key)) return false
        deviceIdHashes[key] = deviceIdHash
        onIdentityRecorded?.invoke(deviceIdHash)
        return true
    }

    fun allDeviceIdHashes(): List<ByteArray> = deviceIdHashes.values.toList()
}
