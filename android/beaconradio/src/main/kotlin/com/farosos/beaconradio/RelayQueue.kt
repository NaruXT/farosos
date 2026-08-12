package com.farosos.beaconradio

import com.farosos.codec.BeaconPacket
import com.farosos.personstate.Scheduler
import com.farosos.personstate.SchedulerToken

/**
 * Cola de retransmisión round-robin (AC del ticket #9, `spec/packet-format.md`):
 * el propio beacon más los beacons ajenos pendientes de relay rotan con
 * una ventana fija.
 *
 * El propio beacon siempre está presente (se reemplaza en el lugar en cada
 * transición); el anuncio de gateway (Máquina B, `GATEWAY_ACTIVO`, ticket
 * #16) ocupa un segundo slot fijo con el mismo trato — presente solo
 * mientras se llame `updateGatewayAnnouncement`, nunca desalojado. Los
 * beacons ajenos tienen un tope: LRU simple normalmente, o la prioridad de
 * `BAJO_CONSUMO` cuando `isLowPower` está activo (ticket #16).
 */
class RelayQueue(
    private val scheduler: Scheduler,
    private val window: Double = 1.0,
    private val foreignCapacity: Int = 20
) {
    private enum class Slot { OWN, GATEWAY, FOREIGN }

    private class Entry(val packet: BeaconPacket, val slot: Slot)

    /**
     * Identifica una entrada por su contenido lógico, no por su posición en
     * la lista — una posición cruda se desincroniza en cuanto el desalojo
     * cambia el tamaño de `foreignEntries` entre rotaciones.
     */
    private sealed class EntryKey {
        object Own : EntryKey()
        object Gateway : EntryKey()
        class Foreign(val deviceIdHash: ByteArray, val nonce: Int) : EntryKey() {
            override fun equals(other: Any?): Boolean =
                other is Foreign && deviceIdHash.contentEquals(other.deviceIdHash) && nonce == other.nonce

            override fun hashCode(): Int = 31 * deviceIdHash.contentHashCode() + nonce
        }
    }

    var onCurrentPacketChanged: ((BeaconPacket) -> Unit)? = null

    /**
     * Se dispara cuando la lista de beacons ajenos cambia entre vacía y
     * no-vacía — fuente de verdad para la transición de vuelta
     * `SINCRONIZADO_IDLE → GATEWAY_ACTIVO` de la Máquina B (ticket #19).
     * `true`: hay al menos una entrada pendiente (pasó de vacía a
     * no-vacía). `false`: volvió a quedar vacía. No se dispara en updates
     * que no cambian ese estado (p. ej. reemplazar una entrada existente
     * por otra con la misma clave).
     */
    var onForeignQueuePendingChanged: ((Boolean) -> Unit)? = null

    /**
     * Señal simple inyectada por quien gobierne la Máquina B — esta clase
     * no depende de `NetworkRoleMachine` directamente. `false` (normal):
     * desalojo LRU puro, igual que Fase 1. `true` (`BAJO_CONSUMO`): un `OK`
     * se descarta primero; si no hay ninguno, no se desaloja nada —
     * `SIN_CONFIRMAR`/`AYUDA`/`SILENCIO_TIMEOUT` nunca se pierden antes que
     * un `OK`, aunque la cola quede momentáneamente sobre su tope.
     */
    var isLowPower: Boolean = false

    private var ownEntry: Entry? = null
    private var gatewayEntry: Entry? = null
    private val foreignEntries = mutableListOf<Entry>() // más antigua al frente
    private var lastShownKey: EntryKey? = null
    private var rotationToken: SchedulerToken? = null

    /** Reemplaza el propio beacon en su lugar en la cola — nunca se desaloja. */
    fun updateOwnBeacon(packet: BeaconPacket) {
        ownEntry = Entry(packet, slot = Slot.OWN)
    }

    /**
     * Ocupa el slot fijo de anuncio de gateway — mismo trato que el beacon
     * propio: nunca se desaloja, se reemplaza en su lugar en cada
     * actualización. Solo tiene sentido llamarlo mientras el nodo está en
     * `GATEWAY_ACTIVO`.
     */
    fun updateGatewayAnnouncement(packet: BeaconPacket) {
        gatewayEntry = Entry(packet, slot = Slot.GATEWAY)
    }

    /** Libera el slot de gateway — se llama al salir de `GATEWAY_ACTIVO`. */
    fun clearGatewayAnnouncement() {
        gatewayEntry = null
    }

    /**
     * Encola un beacon ajeno ya decrementado (`RelayPolicy`) para
     * retransmitir. Si ya había una entrada con la misma clave
     * (deviceIdHash + nonce), la reemplaza en vez de duplicarla.
     */
    fun enqueueForeignBeacon(packet: BeaconPacket) {
        val wasEmpty = foreignEntries.isEmpty()
        foreignEntries.removeAll {
            it.packet.deviceIdHash.contentEquals(packet.deviceIdHash) && it.packet.nonce == packet.nonce
        }
        foreignEntries.add(Entry(packet, slot = Slot.FOREIGN))
        evictIfNeeded()
        notifyForeignQueuePendingChangeIfNeeded(wasEmpty)
    }

    /**
     * Retira una entrada ajena de la cola sin reemplazarla — a diferencia
     * de `enqueueForeignBeacon`, este es el único camino por el que la
     * cola de ajenos puede volver a quedar vacía. Quién la invoca y con
     * qué criterio (p. ej. TTL agotado) queda para un ticket de wiring
     * futuro; por ahora solo existe para que `onForeignQueuePendingChanged`
     * sea alcanzable en ambas direcciones. No-op si la clave no está en
     * la cola.
     */
    fun removeForeignBeacon(deviceIdHash: ByteArray, nonce: Int) {
        val wasEmpty = foreignEntries.isEmpty()
        foreignEntries.removeAll {
            it.packet.deviceIdHash.contentEquals(deviceIdHash) && it.packet.nonce == nonce
        }
        notifyForeignQueuePendingChangeIfNeeded(wasEmpty)
    }

    private fun notifyForeignQueuePendingChangeIfNeeded(wasEmpty: Boolean) {
        val isEmpty = foreignEntries.isEmpty()
        if (wasEmpty == isEmpty) return
        onForeignQueuePendingChanged?.invoke(!isEmpty)
    }

    private fun evictIfNeeded() {
        if (foreignEntries.size <= foreignCapacity) return
        if (!isLowPower) {
            foreignEntries.removeAt(0)
            return
        }
        // El OK más antiguo se descarta primero; si no hay ninguno, no se
        // desaloja nada — proteger lo urgente importa más que respetar el
        // tope exacto.
        val oldestOkIndex = foreignEntries.indexOfFirst { it.packet.status == BeaconPacket.Status.OK }
        if (oldestOkIndex >= 0) {
            foreignEntries.removeAt(oldestOkIndex)
        }
    }

    /** Expone la primera entrada de inmediato y arranca la rotación. */
    fun start() {
        notifyCurrent()
        scheduleNextRotation()
    }

    fun stop() {
        rotationToken?.let { scheduler.cancel(it) }
        rotationToken = null
    }

    private fun allEntries(): List<Entry> = listOfNotNull(ownEntry, gatewayEntry) + foreignEntries

    private fun key(entry: Entry): EntryKey = when (entry.slot) {
        Slot.OWN -> EntryKey.Own
        Slot.GATEWAY -> EntryKey.Gateway
        Slot.FOREIGN -> EntryKey.Foreign(entry.packet.deviceIdHash, entry.packet.nonce)
    }

    private fun scheduleNextRotation() {
        rotationToken = scheduler.schedule(window) { rotate() }
    }

    /**
     * Avanza a la entrada siguiente a la última mostrada, ubicándola por
     * clave en vez de por índice: si esa entrada ya no existe (se desalojó
     * mientras era la actual), reanuda desde el principio en vez de
     * saltarse — a ciegas — la que quedó justo después en la lista.
     */
    private fun rotate() {
        val entries = allEntries()
        if (entries.isEmpty()) {
            scheduleNextRotation()
            return
        }
        val currentPosition = lastShownKey?.let { shownKey -> entries.indexOfFirst { key(it) == shownKey } }
        val nextPosition = ((currentPosition ?: -1) + 1) % entries.size
        notify(entries[nextPosition])
        scheduleNextRotation()
    }

    private fun notifyCurrent() {
        val first = allEntries().firstOrNull() ?: return
        notify(first)
    }

    private fun notify(entry: Entry) {
        lastShownKey = key(entry)
        onCurrentPacketChanged?.invoke(entry.packet)
    }
}
