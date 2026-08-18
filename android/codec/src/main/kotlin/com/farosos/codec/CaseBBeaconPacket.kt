package com.farosos.codec

/**
 * Layout de 27 bytes de Caso B (beacon autenticado, `Versión=0x02`)
 * definido en `spec/packet-format.md`. Reusa `BeaconPacket.MessageType`/
 * `BeaconPacket.Status` (mismos enums, empaquetados en un solo byte
 * `TipoEstado` en este layout) — ver los vectores de prueba en
 * `spec/test-vectors.json`, clave `case_b`.
 */
data class CaseBBeaconPacket(
    val messageType: BeaconPacket.MessageType,
    val deviceIdHash: ByteArray, // 6 bytes
    val status: BeaconPacket.Status,
    val latitudeE7: Int,
    val longitudeE7: Int,
    val timestamp: Long, // uint32, representado como Long para evitar overflow de signo
    val ttl: Int,
    val mac: ByteArray, // 4 bytes
    val sequence: Int
) {
    init {
        require(deviceIdHash.size == 6) { "deviceIdHash debe medir 6 bytes" }
        require(mac.size == 4) { "mac debe medir 4 bytes" }
    }

    override fun equals(other: Any?): Boolean =
        other is CaseBBeaconPacket &&
            messageType == other.messageType &&
            deviceIdHash.contentEquals(other.deviceIdHash) &&
            status == other.status &&
            latitudeE7 == other.latitudeE7 &&
            longitudeE7 == other.longitudeE7 &&
            timestamp == other.timestamp &&
            ttl == other.ttl &&
            mac.contentEquals(other.mac) &&
            sequence == other.sequence

    override fun hashCode(): Int {
        var result = messageType.hashCode()
        result = 31 * result + deviceIdHash.contentHashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + latitudeE7
        result = 31 * result + longitudeE7
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + ttl
        result = 31 * result + mac.contentHashCode()
        result = 31 * result + sequence
        return result
    }

    companion object {
        const val MAGIC: Int = 0xE7
        const val VERSION: Int = 0x02
        const val PACKET_SIZE: Int = 27
    }
}

object CaseBBeaconPacketCodec {
    /** Offsets del layout de 27 bytes documentado en `spec/packet-format.md`. */
    private object Offset {
        const val MAGIC = 0
        const val VERSION = 1
        const val TIPO_ESTADO = 2
        const val DEVICE_ID_HASH = 3 // 6 bytes: 3..8
        const val LATITUDE = 9
        const val LONGITUDE = 13
        const val TIMESTAMP = 17
        const val TTL = 21
        const val MAC = 22 // 4 bytes: 22..25
        const val SEQUENCE = 26
    }

    fun encode(packet: CaseBBeaconPacket): ByteArray {
        val bytes = ByteArray(CaseBBeaconPacket.PACKET_SIZE)
        bytes[Offset.MAGIC] = CaseBBeaconPacket.MAGIC.toByte()
        bytes[Offset.VERSION] = CaseBBeaconPacket.VERSION.toByte()
        bytes[Offset.TIPO_ESTADO] = tipoEstado(packet.messageType, packet.status)
        packet.deviceIdHash.copyInto(bytes, Offset.DEVICE_ID_HASH)
        putLeInt32(bytes, Offset.LATITUDE, packet.latitudeE7)
        putLeInt32(bytes, Offset.LONGITUDE, packet.longitudeE7)
        putLeInt32(bytes, Offset.TIMESTAMP, packet.timestamp.toInt())
        bytes[Offset.TTL] = packet.ttl.toByte()
        packet.mac.copyInto(bytes, Offset.MAC)
        bytes[Offset.SEQUENCE] = packet.sequence.toByte()
        return bytes
    }

    fun decode(bytes: ByteArray): CaseBBeaconPacket? {
        if (bytes.size != CaseBBeaconPacket.PACKET_SIZE) return null
        if ((bytes[Offset.MAGIC].toInt() and 0xFF) != CaseBBeaconPacket.MAGIC) return null
        if ((bytes[Offset.VERSION].toInt() and 0xFF) != CaseBBeaconPacket.VERSION) return null

        val (messageType, status) = decodeTipoEstado(bytes[Offset.TIPO_ESTADO]) ?: return null

        return CaseBBeaconPacket(
            messageType = messageType,
            deviceIdHash = bytes.copyOfRange(Offset.DEVICE_ID_HASH, Offset.LATITUDE),
            status = status,
            latitudeE7 = leInt32(bytes, Offset.LATITUDE),
            longitudeE7 = leInt32(bytes, Offset.LONGITUDE),
            timestamp = leUInt32(bytes, Offset.TIMESTAMP),
            ttl = bytes[Offset.TTL].toInt() and 0xFF,
            mac = bytes.copyOfRange(Offset.MAC, Offset.SEQUENCE),
            sequence = bytes[Offset.SEQUENCE].toInt() and 0xFF
        )
    }

    /** Nibble alto = `Tipo de mensaje`, nibble bajo = `Estado` — decisión 16 de `spec/packet-format.md`. */
    fun tipoEstado(messageType: BeaconPacket.MessageType, status: BeaconPacket.Status): Byte =
        ((messageType.wireValue shl 4) or status.wireValue).toByte()

    private fun decodeTipoEstado(byte: Byte): Pair<BeaconPacket.MessageType, BeaconPacket.Status>? {
        val value = byte.toInt() and 0xFF
        val messageType = BeaconPacket.MessageType.entries.find { it.wireValue == (value shr 4) } ?: return null
        val status = BeaconPacket.Status.entries.find { it.wireValue == (value and 0x0F) } ?: return null
        return messageType to status
    }

    /** `internal` (no `private`) porque `CaseBAuthentication.kt` (mismo módulo) también la necesita. */
    internal fun putLeInt32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun leInt32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun leUInt32(bytes: ByteArray, offset: Int): Long =
        leInt32(bytes, offset).toLong() and 0xFFFFFFFFL
}
