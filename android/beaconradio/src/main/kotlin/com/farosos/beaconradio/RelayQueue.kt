package com.farosos.beaconradio

import com.farosos.codec.BeaconPacket
import com.farosos.personstate.Scheduler
import com.farosos.personstate.SchedulerToken

/**
 * Cola de retransmisión round-robin (AC del ticket #9, `spec/packet-format.md`):
 * el propio beacon más los beacons ajenos pendientes de relay rotan con
 * una ventana fija. Sin priorización por estado en esta fase — todas las
 * entradas tienen el mismo turno.
 */
class RelayQueue(
    private val scheduler: Scheduler,
    private val window: Double = 1.0,
    private val foreignCapacity: Int = 20
) {
    private class Entry(val packet: BeaconPacket, val isOwnBeacon: Boolean)

    /**
     * Identifica una entrada por su contenido lógico, no por su posición en
     * la lista — una posición cruda se desincroniza en cuanto el desalojo
     * LRU cambia el tamaño de `foreignEntries` entre rotaciones.
     */
    private sealed class EntryKey {
        object Own : EntryKey()
        class Foreign(val deviceIdHash: ByteArray, val nonce: Int) : EntryKey() {
            override fun equals(other: Any?): Boolean =
                other is Foreign && deviceIdHash.contentEquals(other.deviceIdHash) && nonce == other.nonce

            override fun hashCode(): Int = 31 * deviceIdHash.contentHashCode() + nonce
        }
    }

    var onCurrentPacketChanged: ((BeaconPacket) -> Unit)? = null

    private var ownEntry: Entry? = null
    private val foreignEntries = mutableListOf<Entry>() // más antigua al frente
    private var lastShownKey: EntryKey? = null
    private var rotationToken: SchedulerToken? = null

    /** Reemplaza el propio beacon en su lugar en la cola — nunca se desaloja. */
    fun updateOwnBeacon(packet: BeaconPacket) {
        ownEntry = Entry(packet, isOwnBeacon = true)
    }

    /**
     * Encola un beacon ajeno ya decrementado (`RelayPolicy`) para
     * retransmitir. Si ya había una entrada con la misma clave
     * (deviceIdHash + nonce), la reemplaza en vez de duplicarla.
     */
    fun enqueueForeignBeacon(packet: BeaconPacket) {
        foreignEntries.removeAll {
            it.packet.deviceIdHash.contentEquals(packet.deviceIdHash) && it.packet.nonce == packet.nonce
        }
        foreignEntries.add(Entry(packet, isOwnBeacon = false))
        if (foreignEntries.size > foreignCapacity) {
            foreignEntries.removeAt(0)
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

    private fun allEntries(): List<Entry> = listOfNotNull(ownEntry) + foreignEntries

    private fun key(entry: Entry): EntryKey =
        if (entry.isOwnBeacon) EntryKey.Own else EntryKey.Foreign(entry.packet.deviceIdHash, entry.packet.nonce)

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
