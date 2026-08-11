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
    // TODO(Fase 1): encode a 26 bytes little-endian. Ver spec/packet-format.md.
    fun encode(packet: BeaconPacket): ByteArray {
        throw NotImplementedError("BeaconPacketCodec.encode no implementado — ver issue de Fase 1")
    }

    // TODO(Fase 1): decode desde 26 bytes little-endian. Ver spec/packet-format.md.
    fun decode(bytes: ByteArray): BeaconPacket? {
        throw NotImplementedError("BeaconPacketCodec.decode no implementado — ver issue de Fase 1")
    }
}
