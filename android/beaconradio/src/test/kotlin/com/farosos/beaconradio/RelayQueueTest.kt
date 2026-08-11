package com.farosos.beaconradio

import com.farosos.codec.BeaconPacket
import kotlin.test.Test
import kotlin.test.assertEquals

class RelayQueueTest {
    private fun packet(deviceByte: Int, nonce: Int, ttl: Int = 10): BeaconPacket = BeaconPacket(
        messageType = BeaconPacket.MessageType.BEACON,
        deviceIdHash = byteArrayOf(deviceByte.toByte(), 0, 0, 0, 0, 0),
        status = BeaconPacket.Status.OK,
        latitudeE7 = 0,
        longitudeE7 = 0,
        timestamp = 0,
        ttl = ttl,
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
}
