package com.farosos.codec

/**
 * Formato de 26 bytes definido en `spec/packet-format.md`. Encode/decode aún
 * no implementado — ver el issue de Fase 1 para el contrato exacto y los
 * vectores de prueba en `spec/test-vectors.json`.
 */
data class BeaconPacket(
    val messageType: MessageType,
    val deviceIdHash: ByteArray, // 6 bytes
    val status: Status,
    val latitudeE7: Int,
    val longitudeE7: Int,
    val timestamp: Long, // uint32, representado como Long para evitar overflow de signo
    val ttl: Int, // uint8, representado como Int
    val nonce: Int, // uint16, representado como Int
    val sequence: Int // uint8, representado como Int
) {
    init {
        require(deviceIdHash.size == 6) { "deviceIdHash debe medir 6 bytes" }
    }

    enum class MessageType(val wireValue: Int) {
        BEACON(0),
        GATEWAY_ANNOUNCE(1),
        ACK_RECEIVED(2)
    }

    enum class Status(val wireValue: Int) {
        SIN_CONFIRMAR(0),
        OK(1),
        AYUDA(2),
        SILENCIO_TIMEOUT(3),
        GATEWAY_DISPONIBLE(4)
    }

    companion object {
        const val MAGIC: Int = 0xE7
        const val VERSION: Int = 0x01
        const val PACKET_SIZE: Int = 26
    }

    override fun equals(other: Any?): Boolean =
        other is BeaconPacket &&
            messageType == other.messageType &&
            deviceIdHash.contentEquals(other.deviceIdHash) &&
            status == other.status &&
            latitudeE7 == other.latitudeE7 &&
            longitudeE7 == other.longitudeE7 &&
            timestamp == other.timestamp &&
            ttl == other.ttl &&
            nonce == other.nonce &&
            sequence == other.sequence

    override fun hashCode(): Int {
        var result = messageType.hashCode()
        result = 31 * result + deviceIdHash.contentHashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + latitudeE7
        result = 31 * result + longitudeE7
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + ttl
        result = 31 * result + nonce
        result = 31 * result + sequence
        return result
    }
}

object BeaconPacketCodec {
    /** Offsets del layout de 26 bytes documentado en `spec/packet-format.md`. */
    private object Offset {
        const val MAGIC = 0
        const val VERSION = 1
        const val MESSAGE_TYPE = 2
        const val DEVICE_ID_HASH = 3 // 6 bytes: 3..8
        const val STATUS = 9
        const val LATITUDE = 10
        const val LONGITUDE = 14
        const val TIMESTAMP = 18
        const val TTL = 22
        const val NONCE = 23
        const val SEQUENCE = 25
    }

    fun encode(packet: BeaconPacket): ByteArray {
        val bytes = ByteArray(BeaconPacket.PACKET_SIZE)
        bytes[Offset.MAGIC] = BeaconPacket.MAGIC.toByte()
        bytes[Offset.VERSION] = BeaconPacket.VERSION.toByte()
        bytes[Offset.MESSAGE_TYPE] = packet.messageType.wireValue.toByte()
        packet.deviceIdHash.copyInto(bytes, Offset.DEVICE_ID_HASH)
        bytes[Offset.STATUS] = packet.status.wireValue.toByte()
        putLeInt32(bytes, Offset.LATITUDE, packet.latitudeE7)
        putLeInt32(bytes, Offset.LONGITUDE, packet.longitudeE7)
        // .toInt() trunca a los 32 bits bajos del Long; para un uint32 válido
        // (siempre < 2^32) ese patrón de bits es exactamente el que putLeInt32
        // debe escribir, incluso si el resultado intermedio queda negativo.
        putLeInt32(bytes, Offset.TIMESTAMP, packet.timestamp.toInt())
        bytes[Offset.TTL] = packet.ttl.toByte()
        putLeUInt16(bytes, Offset.NONCE, packet.nonce)
        bytes[Offset.SEQUENCE] = packet.sequence.toByte()
        return bytes
    }

    private fun putLeUInt16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun putLeInt32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    fun decode(bytes: ByteArray): BeaconPacket? {
        if (bytes.size != BeaconPacket.PACKET_SIZE) return null
        if ((bytes[Offset.MAGIC].toInt() and 0xFF) != BeaconPacket.MAGIC) return null
        if ((bytes[Offset.VERSION].toInt() and 0xFF) != BeaconPacket.VERSION) return null

        val messageType = BeaconPacket.MessageType.entries.find {
            it.wireValue == (bytes[Offset.MESSAGE_TYPE].toInt() and 0xFF)
        } ?: return null
        val status = BeaconPacket.Status.entries.find {
            it.wireValue == (bytes[Offset.STATUS].toInt() and 0xFF)
        } ?: return null

        return BeaconPacket(
            messageType = messageType,
            deviceIdHash = bytes.copyOfRange(Offset.DEVICE_ID_HASH, Offset.STATUS),
            status = status,
            latitudeE7 = leInt32(bytes, Offset.LATITUDE),
            longitudeE7 = leInt32(bytes, Offset.LONGITUDE),
            timestamp = leUInt32(bytes, Offset.TIMESTAMP),
            ttl = bytes[Offset.TTL].toInt() and 0xFF,
            nonce = leUInt16(bytes, Offset.NONCE),
            sequence = bytes[Offset.SEQUENCE].toInt() and 0xFF
        )
    }

    private fun leUInt16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun leInt32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun leUInt32(bytes: ByteArray, offset: Int): Long =
        leInt32(bytes, offset).toLong() and 0xFFFFFFFFL
}
