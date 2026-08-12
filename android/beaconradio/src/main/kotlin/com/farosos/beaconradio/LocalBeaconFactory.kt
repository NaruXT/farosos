package com.farosos.beaconradio

import com.farosos.codec.BeaconPacket

/**
 * Construye el `BeaconPacket` que este nodo emite a partir del estado de la
 * Máquina de estados A (#4/#5).
 *
 * Limitación conocida: sin captura de GPS todavía — lat/long quedan en 0
 * hasta que un ticket futuro integre ubicación real.
 */
object LocalBeaconFactory {
    /**
     * TTL inicial de un beacon recién emitido por este nodo. Se resta 1 por
     * retransmisión — esa lógica pertenece a la ticket de relay (#8/#9), no
     * a esta.
     */
    const val INITIAL_TTL = 16

    fun makeBeacon(
        deviceIdHash: ByteArray,
        status: BeaconPacket.Status,
        sequence: Int,
        nowEpochSeconds: Long,
        nonceGenerator: NonceGenerating
    ): BeaconPacket = BeaconPacket(
        messageType = BeaconPacket.MessageType.BEACON,
        deviceIdHash = deviceIdHash,
        status = status,
        latitudeE7 = 0,
        longitudeE7 = 0,
        timestamp = nowEpochSeconds,
        ttl = INITIAL_TTL,
        nonce = nonceGenerator.nextNonce(),
        sequence = sequence
    )

    /**
     * Construye el anuncio de gateway (`GATEWAY_ANNOUNCE`/`GATEWAY_DISPONIBLE`)
     * que este nodo emite mientras la Máquina B esté en `GATEWAY_ACTIVO`
     * (ticket #17/#21).
     */
    fun makeGatewayAnnouncement(
        deviceIdHash: ByteArray,
        sequence: Int,
        nowEpochSeconds: Long,
        nonceGenerator: NonceGenerating
    ): BeaconPacket = BeaconPacket(
        messageType = BeaconPacket.MessageType.GATEWAY_ANNOUNCE,
        deviceIdHash = deviceIdHash,
        status = BeaconPacket.Status.GATEWAY_DISPONIBLE,
        latitudeE7 = 0,
        longitudeE7 = 0,
        timestamp = nowEpochSeconds,
        ttl = INITIAL_TTL,
        nonce = nonceGenerator.nextNonce(),
        sequence = sequence
    )
}
