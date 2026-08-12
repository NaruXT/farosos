package com.farosos.beaconradio

import com.farosos.codec.BeaconPacket
import kotlin.test.Test
import kotlin.test.assertEquals

class RelayQueueTest {
    private fun packet(
        deviceByte: Int,
        nonce: Int,
        ttl: Int = 10,
        status: BeaconPacket.Status = BeaconPacket.Status.OK
    ): BeaconPacket = BeaconPacket(
        messageType = BeaconPacket.MessageType.BEACON,
        deviceIdHash = byteArrayOf(deviceByte.toByte(), 0, 0, 0, 0, 0),
        status = status,
        latitudeE7 = 0,
        longitudeE7 = 0,
        timestamp = 0,
        ttl = ttl,
        nonce = nonce,
        sequence = 1
    )

    private fun gatewayAnnouncement(deviceByte: Int, nonce: Int): BeaconPacket = BeaconPacket(
        messageType = BeaconPacket.MessageType.GATEWAY_ANNOUNCE,
        deviceIdHash = byteArrayOf(deviceByte.toByte(), 0, 0, 0, 0, 0),
        status = BeaconPacket.Status.GATEWAY_DISPONIBLE,
        latitudeE7 = 0,
        longitudeE7 = 0,
        timestamp = 0,
        ttl = 10,
        nonce = nonce,
        sequence = 1
    )

    @Test
    fun startImmediatelyExposesOwnBeaconWhenItsTheOnlyEntry() {
        val scheduler = FakeScheduler()
        val queue = RelayQueue(scheduler = scheduler, window = 1.0)
        val observed = mutableListOf<BeaconPacket>()
        queue.onCurrentPacketChanged = { observed.add(it) }
        val own = packet(deviceByte = 1, nonce = 1)

        queue.updateOwnBeacon(own)
        queue.start()

        assertEquals(listOf(own), observed)
    }

    @Test
    fun rotatesBetweenOwnAndOneForeignBeacon() {
        val scheduler = FakeScheduler()
        val queue = RelayQueue(scheduler = scheduler, window = 1.0)
        val observed = mutableListOf<BeaconPacket>()
        queue.onCurrentPacketChanged = { observed.add(it) }
        val own = packet(deviceByte = 1, nonce = 1)
        val foreign = packet(deviceByte = 2, nonce = 1)

        queue.updateOwnBeacon(own)
        queue.enqueueForeignBeacon(foreign)
        queue.start()
        scheduler.advance(1.0)
        scheduler.advance(1.0)

        assertEquals(listOf(own, foreign, own), observed)
    }

    @Test
    fun rotatesThroughMultipleForeignBeaconsInOrder() {
        val scheduler = FakeScheduler()
        val queue = RelayQueue(scheduler = scheduler, window = 1.0)
        val observed = mutableListOf<BeaconPacket>()
        queue.onCurrentPacketChanged = { observed.add(it) }
        val own = packet(deviceByte = 1, nonce = 1)
        val foreignA = packet(deviceByte = 2, nonce = 1)
        val foreignB = packet(deviceByte = 3, nonce = 1)

        queue.updateOwnBeacon(own)
        queue.enqueueForeignBeacon(foreignA)
        queue.enqueueForeignBeacon(foreignB)
        queue.start()
        scheduler.advance(1.0)
        scheduler.advance(1.0)
        scheduler.advance(1.0)

        assertEquals(listOf(own, foreignA, foreignB, own), observed)
    }

    @Test
    fun capacityEvictsOldestForeignEntryOnOverflow() {
        val scheduler = FakeScheduler()
        val queue = RelayQueue(scheduler = scheduler, window = 1.0, foreignCapacity = 2)
        val observed = mutableListOf<BeaconPacket>()
        queue.onCurrentPacketChanged = { observed.add(it) }
        val own = packet(deviceByte = 1, nonce = 1)
        val foreign1 = packet(deviceByte = 2, nonce = 1)
        val foreign2 = packet(deviceByte = 3, nonce = 1)
        val foreign3 = packet(deviceByte = 4, nonce = 1)

        queue.updateOwnBeacon(own)
        queue.enqueueForeignBeacon(foreign1)
        queue.enqueueForeignBeacon(foreign2)
        queue.enqueueForeignBeacon(foreign3) // debe desalojar foreign1

        queue.start()
        scheduler.advance(1.0)
        scheduler.advance(1.0)
        scheduler.advance(1.0)

        assertEquals(listOf(own, foreign2, foreign3, own), observed, "foreign1 fue desalojado, no debe aparecer en la rotación")
    }

    @Test
    fun evictingTheCurrentlyShownEntryDoesNotSkipTheNextOne() {
        val scheduler = FakeScheduler()
        val queue = RelayQueue(scheduler = scheduler, window = 1.0, foreignCapacity = 2)
        val observed = mutableListOf<BeaconPacket>()
        queue.onCurrentPacketChanged = { observed.add(it) }
        val own = packet(deviceByte = 1, nonce = 1)
        val foreignA = packet(deviceByte = 2, nonce = 1)
        val foreignB = packet(deviceByte = 3, nonce = 1)
        val foreignC = packet(deviceByte = 4, nonce = 1)

        queue.updateOwnBeacon(own)
        queue.enqueueForeignBeacon(foreignA)
        queue.enqueueForeignBeacon(foreignB)
        queue.start() // muestra own
        scheduler.advance(1.0) // muestra foreignA — esa es la entrada "actual"

        queue.enqueueForeignBeacon(foreignC) // tope=2, desaloja foreignA (justo la que se está mostrando)
        scheduler.advance(1.0)

        assertEquals(
            listOf(own, foreignA, own),
            observed,
            "al desalojarse la entrada mostrada, debe reanudar desde el principio en vez de saltarse foreignB silenciosamente"
        )
    }

    @Test
    fun enqueueingSameKeyReplacesContentWithoutGrowingQueue() {
        val scheduler = FakeScheduler()
        val queue = RelayQueue(scheduler = scheduler, window = 1.0, foreignCapacity = 5)
        val observed = mutableListOf<BeaconPacket>()
        queue.onCurrentPacketChanged = { observed.add(it) }
        val own = packet(deviceByte = 1, nonce = 1)
        val firstSeen = packet(deviceByte = 2, nonce = 1, ttl = 10)
        val sameKeyAgain = packet(deviceByte = 2, nonce = 1, ttl = 3)

        queue.updateOwnBeacon(own)
        queue.enqueueForeignBeacon(firstSeen)
        queue.enqueueForeignBeacon(sameKeyAgain)
        queue.start()
        scheduler.advance(1.0)
        scheduler.advance(1.0)

        assertEquals(listOf(own, sameKeyAgain, own), observed, "misma clave dos veces no debe duplicar la entrada")
    }

    @Test
    fun stopCancelsFurtherRotation() {
        val scheduler = FakeScheduler()
        val queue = RelayQueue(scheduler = scheduler, window = 1.0)
        val observed = mutableListOf<BeaconPacket>()
        queue.onCurrentPacketChanged = { observed.add(it) }
        val own = packet(deviceByte = 1, nonce = 1)
        val foreign = packet(deviceByte = 2, nonce = 1)

        queue.updateOwnBeacon(own)
        queue.enqueueForeignBeacon(foreign)
        queue.start()
        queue.stop()
        scheduler.advance(10.0)

        assertEquals(listOf(own), observed, "tras stop(), no debe seguir rotando")
    }

    // --- Slot de gateway (ticket #16) ---

    @Test
    fun gatewayAnnouncementOccupiesFixedSlotAndParticipatesInRotation() {
        val scheduler = FakeScheduler()
        val queue = RelayQueue(scheduler = scheduler, window = 1.0)
        val observed = mutableListOf<BeaconPacket>()
        queue.onCurrentPacketChanged = { observed.add(it) }
        val own = packet(deviceByte = 1, nonce = 1)
        val gateway = gatewayAnnouncement(deviceByte = 9, nonce = 1)

        queue.updateOwnBeacon(own)
        queue.updateGatewayAnnouncement(gateway)
        queue.start()
        scheduler.advance(1.0)

        assertEquals(listOf(own, gateway), observed)
    }

    @Test
    fun gatewayAnnouncementIsReplacedInPlaceNotDuplicated() {
        val scheduler = FakeScheduler()
        val queue = RelayQueue(scheduler = scheduler, window = 1.0)
        val observed = mutableListOf<BeaconPacket>()
        queue.onCurrentPacketChanged = { observed.add(it) }
        val own = packet(deviceByte = 1, nonce = 1)
        val firstAnnouncement = gatewayAnnouncement(deviceByte = 9, nonce = 1)
        val updatedAnnouncement = gatewayAnnouncement(deviceByte = 9, nonce = 2)

        queue.updateOwnBeacon(own)
        queue.updateGatewayAnnouncement(firstAnnouncement)
        queue.updateGatewayAnnouncement(updatedAnnouncement)
        queue.start()
        scheduler.advance(1.0)
        scheduler.advance(1.0)

        assertEquals(listOf(own, updatedAnnouncement, own), observed, "un solo slot de gateway, reemplazado en su lugar")
    }

    @Test
    fun gatewayAnnouncementNeverEvicted() {
        val scheduler = FakeScheduler()
        val queue = RelayQueue(scheduler = scheduler, window = 1.0, foreignCapacity = 1)
        val observed = mutableListOf<BeaconPacket>()
        queue.onCurrentPacketChanged = { observed.add(it) }
        val own = packet(deviceByte = 1, nonce = 1)
        val gateway = gatewayAnnouncement(deviceByte = 9, nonce = 1)
        val foreignA = packet(deviceByte = 2, nonce = 1)
        val foreignB = packet(deviceByte = 3, nonce = 1) // desaloja foreignA (tope=1), nunca al gateway

        queue.updateOwnBeacon(own)
        queue.updateGatewayAnnouncement(gateway)
        queue.enqueueForeignBeacon(foreignA)
        queue.enqueueForeignBeacon(foreignB)
        queue.start()
        scheduler.advance(1.0)
        scheduler.advance(1.0)

        assertEquals(listOf(own, gateway, foreignB), observed)
    }

    @Test
    fun clearGatewayAnnouncementRemovesItFromRotation() {
        val scheduler = FakeScheduler()
        val queue = RelayQueue(scheduler = scheduler, window = 1.0)
        val observed = mutableListOf<BeaconPacket>()
        queue.onCurrentPacketChanged = { observed.add(it) }
        val own = packet(deviceByte = 1, nonce = 1)
        val gateway = gatewayAnnouncement(deviceByte = 9, nonce = 1)

        queue.updateOwnBeacon(own)
        queue.updateGatewayAnnouncement(gateway)
        queue.clearGatewayAnnouncement()
        queue.start()
        scheduler.advance(1.0)

        assertEquals(listOf(own, own), observed, "sin slot de gateway, la rotación solo tiene al propio beacon")
    }

    // --- Prioridad de descarte bajo BAJO_CONSUMO (ticket #16) ---

    @Test
    fun capacityStaysLruWhenNotLowPower() {
        val scheduler = FakeScheduler()
        val queue = RelayQueue(scheduler = scheduler, window = 1.0, foreignCapacity = 2)
        val observed = mutableListOf<BeaconPacket>()
        queue.onCurrentPacketChanged = { observed.add(it) }
        val own = packet(deviceByte = 1, nonce = 1)
        val foreignA = packet(deviceByte = 2, nonce = 1, status = BeaconPacket.Status.AYUDA)
        val foreignB = packet(deviceByte = 3, nonce = 1, status = BeaconPacket.Status.OK)
        val foreignC = packet(deviceByte = 4, nonce = 1, status = BeaconPacket.Status.OK)

        queue.updateOwnBeacon(own)
        queue.enqueueForeignBeacon(foreignA)
        queue.enqueueForeignBeacon(foreignB)
        queue.enqueueForeignBeacon(foreignC) // isLowPower=false (default): LRU puro, desaloja foreignA aunque sea AYUDA

        queue.start()
        scheduler.advance(1.0)
        scheduler.advance(1.0)
        scheduler.advance(1.0)

        assertEquals(listOf(own, foreignB, foreignC, own), observed, "fuera de bajo consumo, la prioridad no aplica")
    }

    @Test
    fun lowPowerEvictsOkEntryFirstEvenIfOlderEntriesArePending() {
        val scheduler = FakeScheduler()
        val queue = RelayQueue(scheduler = scheduler, window = 1.0, foreignCapacity = 2)
        val observed = mutableListOf<BeaconPacket>()
        queue.onCurrentPacketChanged = { observed.add(it) }
        val own = packet(deviceByte = 1, nonce = 1)
        val foreignA = packet(deviceByte = 2, nonce = 1, status = BeaconPacket.Status.AYUDA) // más antiguo, protegido
        val foreignB = packet(deviceByte = 3, nonce = 1, status = BeaconPacket.Status.OK) // más nuevo, pero OK
        val foreignC = packet(deviceByte = 4, nonce = 1, status = BeaconPacket.Status.SILENCIO_TIMEOUT)

        queue.isLowPower = true
        queue.updateOwnBeacon(own)
        queue.enqueueForeignBeacon(foreignA)
        queue.enqueueForeignBeacon(foreignB)
        queue.enqueueForeignBeacon(foreignC) // tope=2: debe desalojar foreignB (OK), no foreignA (AYUDA)

        queue.start()
        scheduler.advance(1.0)
        scheduler.advance(1.0)
        scheduler.advance(1.0)

        assertEquals(listOf(own, foreignA, foreignC, own), observed, "el OK se descarta antes que el AYUDA más antiguo")
    }

    @Test
    fun lowPowerNeverEvictsProtectedStatusWhenNoOkAvailable() {
        val scheduler = FakeScheduler()
        val queue = RelayQueue(scheduler = scheduler, window = 1.0, foreignCapacity = 2)
        val observed = mutableListOf<BeaconPacket>()
        queue.onCurrentPacketChanged = { observed.add(it) }
        val own = packet(deviceByte = 1, nonce = 1)
        val foreignA = packet(deviceByte = 2, nonce = 1, status = BeaconPacket.Status.AYUDA)
        val foreignB = packet(deviceByte = 3, nonce = 1, status = BeaconPacket.Status.SILENCIO_TIMEOUT)
        val foreignC = packet(deviceByte = 4, nonce = 1, status = BeaconPacket.Status.SIN_CONFIRMAR) // sin ningún OK disponible

        queue.isLowPower = true
        queue.updateOwnBeacon(own)
        queue.enqueueForeignBeacon(foreignA)
        queue.enqueueForeignBeacon(foreignB)
        queue.enqueueForeignBeacon(foreignC) // no debe desalojar nada — la cola crece por encima del tope

        queue.start()
        scheduler.advance(1.0)
        scheduler.advance(1.0)
        scheduler.advance(1.0)
        scheduler.advance(1.0)

        assertEquals(
            listOf(own, foreignA, foreignB, foreignC, own),
            observed,
            "sin ningún OK que sacrificar, ninguna entrada protegida se pierde"
        )
    }

    @Test
    fun lowPowerEvictsOldestOkAmongMultipleOkEntries() {
        val scheduler = FakeScheduler()
        val queue = RelayQueue(scheduler = scheduler, window = 1.0, foreignCapacity = 2)
        val observed = mutableListOf<BeaconPacket>()
        queue.onCurrentPacketChanged = { observed.add(it) }
        val own = packet(deviceByte = 1, nonce = 1)
        val foreignA = packet(deviceByte = 2, nonce = 1, status = BeaconPacket.Status.OK) // OK más antiguo
        val foreignB = packet(deviceByte = 3, nonce = 1, status = BeaconPacket.Status.OK)
        val foreignC = packet(deviceByte = 4, nonce = 1, status = BeaconPacket.Status.AYUDA)

        queue.isLowPower = true
        queue.updateOwnBeacon(own)
        queue.enqueueForeignBeacon(foreignA)
        queue.enqueueForeignBeacon(foreignB)
        queue.enqueueForeignBeacon(foreignC) // tope=2: desaloja el OK más antiguo (foreignA), no foreignB

        queue.start()
        scheduler.advance(1.0)
        scheduler.advance(1.0)
        scheduler.advance(1.0)

        assertEquals(listOf(own, foreignB, foreignC, own), observed)
    }

    // --- Señal de cola de ajenos vacía↔no-vacía (ticket #19) ---

    @Test
    fun foreignQueuePendingChangedFiresTrueOnFirstForeignBeacon() {
        val scheduler = FakeScheduler()
        val queue = RelayQueue(scheduler = scheduler)
        val observed = mutableListOf<Boolean>()
        queue.onForeignQueuePendingChanged = { observed.add(it) }
        val foreign = packet(deviceByte = 2, nonce = 1)

        queue.enqueueForeignBeacon(foreign)

        assertEquals(listOf(true), observed)
    }

    @Test
    fun foreignQueuePendingChangedNotFiredOnSubsequentEnqueueWhileNonEmpty() {
        val scheduler = FakeScheduler()
        val queue = RelayQueue(scheduler = scheduler)
        val observed = mutableListOf<Boolean>()
        val foreignA = packet(deviceByte = 2, nonce = 1)
        val foreignB = packet(deviceByte = 3, nonce = 1)
        queue.enqueueForeignBeacon(foreignA)
        queue.onForeignQueuePendingChanged = { observed.add(it) }

        queue.enqueueForeignBeacon(foreignB)

        assertEquals(emptyList(), observed)
    }

    @Test
    fun foreignQueuePendingChangedNotFiredWhenReplacingSameKey() {
        val scheduler = FakeScheduler()
        val queue = RelayQueue(scheduler = scheduler)
        val observed = mutableListOf<Boolean>()
        val firstSeen = packet(deviceByte = 2, nonce = 1, ttl = 10)
        val sameKeyAgain = packet(deviceByte = 2, nonce = 1, ttl = 3)
        queue.enqueueForeignBeacon(firstSeen)
        queue.onForeignQueuePendingChanged = { observed.add(it) }

        queue.enqueueForeignBeacon(sameKeyAgain)

        assertEquals(emptyList(), observed, "reemplazar la misma clave no cambia el estado vacío/no-vacío")
    }

    @Test
    fun foreignQueuePendingChangedFiresFalseWhenLastForeignBeaconRemoved() {
        val scheduler = FakeScheduler()
        val queue = RelayQueue(scheduler = scheduler)
        val observed = mutableListOf<Boolean>()
        val foreign = packet(deviceByte = 2, nonce = 1)
        queue.enqueueForeignBeacon(foreign)
        queue.onForeignQueuePendingChanged = { observed.add(it) }

        queue.removeForeignBeacon(deviceIdHash = foreign.deviceIdHash, nonce = foreign.nonce)

        assertEquals(listOf(false), observed)
    }

    @Test
    fun foreignQueuePendingChangedNotFiredWhenRemovingWithOthersStillPending() {
        val scheduler = FakeScheduler()
        val queue = RelayQueue(scheduler = scheduler)
        val observed = mutableListOf<Boolean>()
        val foreignA = packet(deviceByte = 2, nonce = 1)
        val foreignB = packet(deviceByte = 3, nonce = 1)
        queue.enqueueForeignBeacon(foreignA)
        queue.enqueueForeignBeacon(foreignB)
        queue.onForeignQueuePendingChanged = { observed.add(it) }

        queue.removeForeignBeacon(deviceIdHash = foreignA.deviceIdHash, nonce = foreignA.nonce)

        assertEquals(emptyList(), observed)
    }

    @Test
    fun removeForeignBeaconIsNoOpForUnknownKey() {
        val scheduler = FakeScheduler()
        val queue = RelayQueue(scheduler = scheduler)
        val observed = mutableListOf<Boolean>()
        queue.onForeignQueuePendingChanged = { observed.add(it) }

        queue.removeForeignBeacon(deviceIdHash = byteArrayOf(9, 9, 9, 9, 9, 9), nonce = 99)

        assertEquals(emptyList(), observed)
    }

    @Test
    fun removeForeignBeaconRemovesItFromRotation() {
        val scheduler = FakeScheduler()
        val queue = RelayQueue(scheduler = scheduler, window = 1.0)
        val observed = mutableListOf<BeaconPacket>()
        queue.onCurrentPacketChanged = { observed.add(it) }
        val own = packet(deviceByte = 1, nonce = 1)
        val foreign = packet(deviceByte = 2, nonce = 1)

        queue.updateOwnBeacon(own)
        queue.enqueueForeignBeacon(foreign)
        queue.removeForeignBeacon(deviceIdHash = foreign.deviceIdHash, nonce = foreign.nonce)
        queue.start()
        scheduler.advance(1.0)

        assertEquals(listOf(own, own), observed, "sin beacons ajenos, la rotación solo tiene al propio beacon")
    }
}
