package com.farosos.codec

/**
 * Fragmento de `FRAGMENTO_FIRMA` (Caso A, layout legado `Versión=0x01`,
 * `Tipo=3`) definido en `spec/packet-format.md`. Un dispositivo que nunca
 * tuvo conectividad fragmenta `pubkey Ed25519 (32B) || firma Ed25519 (64B)`
 * (96 bytes) en 7 fragmentos de 15 bytes — ver `SignatureFragmenter` para
 * fragmentar/reensamblar el payload completo. Vectores de prueba en
 * `spec/test-vectors.json`, clave `fragmento_firma`.
 */
data class FragmentoFirmaPacket(
    val deviceIdHash: ByteArray, // 6 bytes
    val ttl: Int,
    val fragmentIndex: Int, // 0-6
    val fragmentCount: Int, // siempre 7 hoy (96 bytes / 15 por fragmento)
    // Bytes REALES del fragmento, sin el relleno de ceros que sí lleva el
    // paquete en el aire — 15 bytes, salvo el último fragmento (6 bytes).
    val chunk: ByteArray
) {
    init {
        require(deviceIdHash.size == 6) { "deviceIdHash debe medir 6 bytes" }
        require(chunk.size <= PAYLOAD_CHUNK_SIZE) { "chunk no puede superar $PAYLOAD_CHUNK_SIZE bytes" }
        require(fragmentIndex in 0..0x0F && fragmentCount in 0..0x0F) { "índice y conteo deben caber en un nibble (0-15)" }
    }

    override fun equals(other: Any?): Boolean =
        other is FragmentoFirmaPacket &&
            deviceIdHash.contentEquals(other.deviceIdHash) &&
            ttl == other.ttl &&
            fragmentIndex == other.fragmentIndex &&
            fragmentCount == other.fragmentCount &&
            chunk.contentEquals(other.chunk)

    override fun hashCode(): Int {
        var result = deviceIdHash.contentHashCode()
        result = 31 * result + ttl
        result = 31 * result + fragmentIndex
        result = 31 * result + fragmentCount
        result = 31 * result + chunk.contentHashCode()
        return result
    }

    companion object {
        const val MAGIC: Int = 0xE7
        const val VERSION: Int = 0x01
        const val MESSAGE_TYPE: Int = 3
        const val PACKET_SIZE: Int = 26
        const val PAYLOAD_CHUNK_SIZE: Int = 15

        // pubkey Ed25519 (32B) + firma Ed25519 (64B) — tamaño fijo por
        // construcción (Ed25519 no cambia de tamaño), usado para saber
        // cuántos bytes reales trae el último fragmento sin necesitar un
        // campo de longitud aparte (ver FragmentoFirmaPacketCodec.decode).
        const val TOTAL_PAYLOAD_SIZE: Int = 96
    }
}

object FragmentoFirmaPacketCodec {
    /** Offsets del layout de 26 bytes documentado en `spec/packet-format.md`. */
    private object Offset {
        const val MAGIC = 0
        const val VERSION = 1
        const val MESSAGE_TYPE = 2
        const val DEVICE_ID_HASH = 3 // 6 bytes: 3..8
        const val TTL = 9
        const val FRAG_HEADER = 10
        const val PAYLOAD = 11 // 15 bytes: 11..25
    }

    fun encode(packet: FragmentoFirmaPacket): ByteArray {
        val bytes = ByteArray(FragmentoFirmaPacket.PACKET_SIZE)
        bytes[Offset.MAGIC] = FragmentoFirmaPacket.MAGIC.toByte()
        bytes[Offset.VERSION] = FragmentoFirmaPacket.VERSION.toByte()
        bytes[Offset.MESSAGE_TYPE] = FragmentoFirmaPacket.MESSAGE_TYPE.toByte()
        packet.deviceIdHash.copyInto(bytes, Offset.DEVICE_ID_HASH)
        bytes[Offset.TTL] = packet.ttl.toByte()
        bytes[Offset.FRAG_HEADER] = fragHeader(packet.fragmentIndex, packet.fragmentCount)
        packet.chunk.copyInto(bytes, Offset.PAYLOAD) // el resto del payload queda en 0 (relleno)
        return bytes
    }

    fun decode(bytes: ByteArray): FragmentoFirmaPacket? {
        if (bytes.size != FragmentoFirmaPacket.PACKET_SIZE) return null
        if ((bytes[Offset.MAGIC].toInt() and 0xFF) != FragmentoFirmaPacket.MAGIC) return null
        if ((bytes[Offset.VERSION].toInt() and 0xFF) != FragmentoFirmaPacket.VERSION) return null
        if ((bytes[Offset.MESSAGE_TYPE].toInt() and 0xFF) != FragmentoFirmaPacket.MESSAGE_TYPE) return null

        val deviceIdHash = bytes.copyOfRange(Offset.DEVICE_ID_HASH, Offset.TTL)
        val ttl = bytes[Offset.TTL].toInt() and 0xFF
        val (index, count) = decodeFragHeader(bytes[Offset.FRAG_HEADER])
        if (count <= 0 || index >= count) return null
        val realLength = realChunkLength(index, count)
        val chunk = bytes.copyOfRange(Offset.PAYLOAD, Offset.PAYLOAD + realLength)

        return FragmentoFirmaPacket(
            deviceIdHash = deviceIdHash,
            ttl = ttl,
            fragmentIndex = index,
            fragmentCount = count,
            chunk = chunk
        )
    }

    /** Nibble alto = índice de fragmento, nibble bajo = conteo total. */
    fun fragHeader(index: Int, count: Int): Byte = ((index shl 4) or count).toByte()

    private fun decodeFragHeader(byte: Byte): Pair<Int, Int> {
        val value = byte.toInt() and 0xFF
        return (value shr 4) to (value and 0x0F)
    }

    /**
     * El último fragmento (`index == count - 1`) trae menos de
     * `PAYLOAD_CHUNK_SIZE` bytes reales — el resto del payload de 15 bytes
     * en el paquete es relleno de ceros, recortado aquí sin necesitar un
     * campo de longitud aparte.
     */
    private fun realChunkLength(index: Int, count: Int): Int {
        if (index != count - 1) return FragmentoFirmaPacket.PAYLOAD_CHUNK_SIZE
        val consumedByEarlierFragments = (count - 1) * FragmentoFirmaPacket.PAYLOAD_CHUNK_SIZE
        return FragmentoFirmaPacket.TOTAL_PAYLOAD_SIZE - consumedByEarlierFragments
    }
}
