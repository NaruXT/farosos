package com.farosos.beaconradio

import com.farosos.codec.BeaconPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LocalBeaconFactoryTest {
    private class FixedNonceGenerator(private val value: Int) : NonceGenerating {
        override fun nextNonce(): Int = value
    }

    @Test
    fun makeBeaconFillsFieldsFromInputs() {
        val deviceIdHash = byteArrayOf(9, 8, 7, 6, 5, 4)

        val packet = LocalBeaconFactory.makeBeacon(
            deviceIdHash = deviceIdHash,
            status = BeaconPacket.Status.AYUDA,
            sequence = 5,
            nowEpochSeconds = 1_700_000_000L,
            nonceGenerator = FixedNonceGenerator(0x1234)
        )

        assertEquals(BeaconPacket.MessageType.BEACON, packet.messageType)
        assertEquals(deviceIdHash, packet.deviceIdHash)
        assertEquals(BeaconPacket.Status.AYUDA, packet.status)
        assertEquals(5, packet.sequence)
        assertEquals(1_700_000_000L, packet.timestamp)
        assertEquals(0x1234, packet.nonce)
        assertEquals(LocalBeaconFactory.INITIAL_TTL, packet.ttl)
    }

    @Test
    fun makeBeaconUsesFreshNoncePerCall() {
        val deviceIdHash = byteArrayOf(9, 8, 7, 6, 5, 4)
        var nextValue = 1
        val generator = object : NonceGenerating {
            override fun nextNonce(): Int = nextValue++
        }

        val first = LocalBeaconFactory.makeBeacon(deviceIdHash, BeaconPacket.Status.OK, 1, 0L, generator)
        val second = LocalBeaconFactory.makeBeacon(deviceIdHash, BeaconPacket.Status.OK, 1, 0L, generator)

        assertNotEquals(first.nonce, second.nonce)
    }
}
