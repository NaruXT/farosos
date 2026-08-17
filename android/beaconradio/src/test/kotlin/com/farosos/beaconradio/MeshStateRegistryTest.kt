package com.farosos.beaconradio

import com.farosos.codec.BeaconPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeshStateRegistryTest {
    private fun makePacket(
        deviceIdHash: ByteArray = byteArrayOf(1, 2, 3, 4, 5, 6),
        status: BeaconPacket.Status = BeaconPacket.Status.OK,
        sequence: Int
    ): BeaconPacket = BeaconPacket(
        messageType = BeaconPacket.MessageType.BEACON,
        deviceIdHash = deviceIdHash,
        status = status,
        latitudeE7 = 10,
        longitudeE7 = 20,
        timestamp = 1_700_000_000L,
        ttl = 16,
        nonce = 0x1234,
        sequence = sequence
    )

    @Test
    fun updateStoresFirstStateSeenForADevice() {
        val registry = MeshStateRegistry()
        val packet = makePacket(sequence = 1)

        val accepted = registry.update(packet)

        assertTrue(accepted)
        assertEquals(listOf(MeshParticipantState.from(packet)), registry.allStates())
    }

    @Test
    fun updateAcceptsStrictlyNewerSequence() {
        val registry = MeshStateRegistry()
        registry.update(makePacket(sequence = 1))

        val accepted = registry.update(makePacket(status = BeaconPacket.Status.AYUDA, sequence = 2))

        assertTrue(accepted)
        assertEquals(BeaconPacket.Status.AYUDA, registry.allStates().first().status)
        assertEquals(2, registry.allStates().first().sequence)
    }

    @Test
    fun updateRejectsEqualOrOlderSequence() {
        val registry = MeshStateRegistry()
        registry.update(makePacket(status = BeaconPacket.Status.AYUDA, sequence = 5))

        val acceptedEqual = registry.update(makePacket(status = BeaconPacket.Status.OK, sequence = 5))
        val acceptedOlder = registry.update(makePacket(status = BeaconPacket.Status.OK, sequence = 3))

        assertFalse(acceptedEqual)
        assertFalse(acceptedOlder)
        assertEquals(BeaconPacket.Status.AYUDA, registry.allStates().first().status) // sin cambios
    }

    @Test
    fun updateTracksMultipleDevicesIndependently() {
        val registry = MeshStateRegistry()
        val deviceA = byteArrayOf(1, 1, 1, 1, 1, 1)
        val deviceB = byteArrayOf(2, 2, 2, 2, 2, 2)

        registry.update(makePacket(deviceIdHash = deviceA, sequence = 1))
        registry.update(makePacket(deviceIdHash = deviceB, sequence = 1))

        val seenHashes = registry.allStates().map { it.deviceIdHash.toList() }.toSet()
        assertEquals(setOf(deviceA.toList(), deviceB.toList()), seenHashes)
    }

    @Test
    fun onStateUpdatedFiresOnlyWhenAccepted() {
        val registry = MeshStateRegistry()
        val observed = mutableListOf<Int>()
        registry.onStateUpdated = { observed.add(it.sequence) }

        registry.update(makePacket(sequence = 1))
        registry.update(makePacket(sequence = 1)) // rechazado, no dispara
        registry.update(makePacket(sequence = 2))

        assertEquals(listOf(1, 2), observed)
    }
}
