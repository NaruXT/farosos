package com.farosos.beaconradio

import com.farosos.codec.BeaconPacket

/**
 * Decide qué hacer con un beacon ajeno recién aceptado por la caché de
 * dedup (ticket #7, `spec/packet-format.md`). El TTL se decrementa una
 * sola vez, al aceptarlo para relay — no cada vez que este nodo lo vuelve
 * a anunciar mientras está en la cola.
 */
object RelayPolicy {
    /**
     * `null` si el TTL recibido ya es 0 (agotado, nunca se retransmite).
     * En cualquier otro caso, devuelve el mismo paquete con el TTL
     * decrementado en 1 — incluso si el resultado queda en 0, ese salto
     * sí se retransmite una vez; morirá en el próximo nodo.
     */
    fun decrementedForRelay(packet: BeaconPacket): BeaconPacket? {
        if (packet.ttl <= 0) return null
        return packet.copy(ttl = packet.ttl - 1)
    }
}
