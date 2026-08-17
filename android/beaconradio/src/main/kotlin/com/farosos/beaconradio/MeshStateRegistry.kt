package com.farosos.beaconradio

import com.farosos.codec.BeaconPacket

/**
 * Estado más reciente conocido de un participante de la malla — lo que un
 * gateway sube a `mesh_states` (ticket #32, ADR-0002).
 */
data class MeshParticipantState(
    val deviceIdHash: ByteArray,
    val status: BeaconPacket.Status,
    val latitudeE7: Int,
    val longitudeE7: Int,
    val timestamp: Long,
    val sequence: Int
) {
    companion object {
        fun from(packet: BeaconPacket): MeshParticipantState = MeshParticipantState(
            deviceIdHash = packet.deviceIdHash,
            status = packet.status,
            latitudeE7 = packet.latitudeE7,
            longitudeE7 = packet.longitudeE7,
            timestamp = packet.timestamp,
            sequence = packet.sequence
        )
    }

    override fun equals(other: Any?): Boolean =
        other is MeshParticipantState &&
            deviceIdHash.contentEquals(other.deviceIdHash) &&
            status == other.status &&
            latitudeE7 == other.latitudeE7 &&
            longitudeE7 == other.longitudeE7 &&
            timestamp == other.timestamp &&
            sequence == other.sequence

    override fun hashCode(): Int {
        var result = deviceIdHash.contentHashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + latitudeE7
        result = 31 * result + longitudeE7
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + sequence
        return result
    }
}

/**
 * Guarda el último estado conocido de la malla por `deviceIdHash` — "lo que
 * este teléfono sabe", para subir al backend de agregación al entrar a
 * `GATEWAY_ACTIVO` (ticket #32). Se alimenta desde dos puntos en la capa de
 * app, sin importar el rol de red actual (para que el snapshot inicial al
 * activarse como gateway ya incluya lo visto antes de convertirse en
 * gateway): el seam de `handleReceivedPacketData` (beacons ajenos) y el de
 * `refreshAdvertisedBeacon` (el propio estado — nunca llega por el primer
 * camino porque se auto-descarta como duplicado al rebotar, decisión 12).
 */
class MeshStateRegistry {
    /** Se dispara solo cuando `update` acepta un estado nuevo — no en cada llamada. */
    var onStateUpdated: ((MeshParticipantState) -> Unit)? = null

    private val states = mutableMapOf<String, MeshParticipantState>()

    /**
     * Acepta el paquete solo si su secuencia es estrictamente más nueva que
     * la que ya se conocía para ese `deviceIdHash` (decisión: nueva
     * Secuencia > vieja, sin manejo de wraparound — fuera de alcance de
     * este ticket). Devuelve si se aceptó.
     */
    fun update(packet: BeaconPacket): Boolean {
        val key = MeshStateIds.deviceIdHashHex(packet.deviceIdHash)
        val existing = states[key]
        if (existing != null && existing.sequence >= packet.sequence) return false
        val state = MeshParticipantState.from(packet)
        states[key] = state
        onStateUpdated?.invoke(state)
        return true
    }

    fun allStates(): List<MeshParticipantState> = states.values.toList()
}
