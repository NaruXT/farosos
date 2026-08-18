package com.farosos.codec

/**
 * Fragmenta/reensambla `pubkey Ed25519 (32B) || firma Ed25519 (64B)` (96
 * bytes) en/desde los 7 `FragmentoFirmaPacket` de Caso A —
 * `spec/packet-format.md`, sección `FRAGMENTO_FIRMA`. Puro: no decide qué
 * hacer con un payload reensamblado (partirlo en pubkey/firma y verificar
 * es responsabilidad de quien llama, p. ej. un ensamblador con estado en
 * `:beaconradio`).
 */
object SignatureFragmenter {
    /**
     * Corta `publicKey || signature` en fragmentos consecutivos de
     * `FragmentoFirmaPacket.PAYLOAD_CHUNK_SIZE` bytes — el último trae menos.
     */
    fun fragment(publicKey: ByteArray, signature: ByteArray, deviceIdHash: ByteArray, ttl: Int): List<FragmentoFirmaPacket> {
        require(publicKey.size == CaseASignature.PUBLIC_KEY_LENGTH) { "publicKey debe medir ${CaseASignature.PUBLIC_KEY_LENGTH} bytes" }
        require(signature.size == CaseASignature.SIGNATURE_LENGTH) { "signature debe medir ${CaseASignature.SIGNATURE_LENGTH} bytes" }
        val payload = publicKey + signature
        val chunkSize = FragmentoFirmaPacket.PAYLOAD_CHUNK_SIZE
        val count = (payload.size + chunkSize - 1) / chunkSize

        return (0 until count).map { index ->
            val start = index * chunkSize
            val end = minOf(start + chunkSize, payload.size)
            FragmentoFirmaPacket(
                deviceIdHash = deviceIdHash,
                ttl = ttl,
                fragmentIndex = index,
                fragmentCount = count,
                chunk = payload.copyOfRange(start, end)
            )
        }
    }

    /**
     * Reensambla el payload completo (96 bytes) a partir de fragmentos del
     * mismo `device_id_hash` — el orden de [fragments] no importa. Devuelve
     * `null` si faltan fragmentos, si declaran conteos distintos entre sí, o
     * si dos fragmentos con el mismo índice traen contenido distinto
     * (corrupción/manipulación).
     */
    fun reassemble(fragments: List<FragmentoFirmaPacket>): ByteArray? {
        val first = fragments.firstOrNull() ?: return null
        val deviceIdHash = first.deviceIdHash
        val count = first.fragmentCount
        if (fragments.any { !it.deviceIdHash.contentEquals(deviceIdHash) || it.fragmentCount != count }) return null

        val chunkByIndex = mutableMapOf<Int, ByteArray>()
        for (fragment in fragments) {
            val existing = chunkByIndex[fragment.fragmentIndex]
            if (existing != null && !existing.contentEquals(fragment.chunk)) return null
            chunkByIndex[fragment.fragmentIndex] = fragment.chunk
        }
        if (chunkByIndex.size != count) return null

        var payload = ByteArray(0)
        for (index in 0 until count) {
            val chunk = chunkByIndex[index] ?: return null
            payload += chunk
        }
        return payload
    }

    /**
     * Inversa de la concatenación usada en [fragment]: separa un payload
     * reensamblado (96 bytes) en `pubkey`/`firma`. `null` si no mide el
     * tamaño esperado.
     */
    fun split(payload: ByteArray): Pair<ByteArray, ByteArray>? {
        if (payload.size != FragmentoFirmaPacket.TOTAL_PAYLOAD_SIZE) return null
        val publicKey = payload.copyOfRange(0, CaseASignature.PUBLIC_KEY_LENGTH)
        val signature = payload.copyOfRange(CaseASignature.PUBLIC_KEY_LENGTH, payload.size)
        return publicKey to signature
    }
}
