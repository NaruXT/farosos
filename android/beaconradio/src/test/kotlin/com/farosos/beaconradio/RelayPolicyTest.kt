package com.farosos.beaconradio

import com.farosos.codec.BeaconPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RelayPolicyTest {
    private fun packet(ttl: Int): BeaconPacket = BeaconPacket(
        messageType = BeaconPacket.MessageType.BEACON,
        deviceIdHash = byteArrayOf(1, 2, 3, 4, 5, 6),
        status = BeaconPacket.Status.OK,
        latitudeE7 = 0,
        longitudeE7 = 0,
        timestamp = 0,
        ttl = ttl,
        nonce = 42,
        sequence = 1
    )

    @Test
    fun ttlZeroIsNeverRelayed() {
        assertNull(RelayPolicy.decrementedForRelay(packet(ttl = 0)))
    }

    @Test
    fun ttlDecrementsByOneWhenRelayed() {
        val relayed = RelayPolicy.decrementedForRelay(packet(ttl = 5))
        assertEquals(4, relayed?.ttl)
    }

    @Test
    fun ttlOneStillGetsRelayedOnceWithZeroResultingTtl() {
        val relayed = RelayPolicy.decrementedForRelay(packet(ttl = 1))
        assertEquals(0, relayed?.ttl)
    }

    @Test
    fun relayedPacketPreservesOtherFields() {
        val original = packet(ttl = 10)
        val relayed = RelayPolicy.decrementedForRelay(original)
        assertEquals(original.deviceIdHash, relayed?.deviceIdHash)
        assertEquals(original.nonce, relayed?.nonce)
        assertEquals(original.sequence, relayed?.sequence)
        assertEquals(original.status, relayed?.status)
    }
}
