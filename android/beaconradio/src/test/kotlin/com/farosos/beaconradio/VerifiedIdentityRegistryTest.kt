package com.farosos.beaconradio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerifiedIdentityRegistryTest {
    @Test
    fun recordStoresFirstDeviceIdHashSeen() {
        val registry = VerifiedIdentityRegistry()
        val hash = byteArrayOf(1, 2, 3, 4, 5, 6)

        val accepted = registry.record(hash)

        assertTrue(accepted)
        assertEquals(1, registry.allDeviceIdHashes().size)
        assertTrue(hash.contentEquals(registry.allDeviceIdHashes().first()))
    }

    @Test
    fun recordRejectsTheSameDeviceIdHashTwice() {
        val registry = VerifiedIdentityRegistry()
        val hash = byteArrayOf(1, 2, 3, 4, 5, 6)
        registry.record(hash)

        val acceptedAgain = registry.record(hash)

        assertFalse(acceptedAgain)
        assertEquals(1, registry.allDeviceIdHashes().size)
    }

    @Test
    fun recordTracksMultipleDevicesIndependently() {
        val registry = VerifiedIdentityRegistry()
        val deviceA = byteArrayOf(1, 1, 1, 1, 1, 1)
        val deviceB = byteArrayOf(2, 2, 2, 2, 2, 2)

        registry.record(deviceA)
        registry.record(deviceB)

        val hexes = registry.allDeviceIdHashes().map { MeshStateIds.deviceIdHashHex(it) }.toSet()
        assertEquals(setOf(MeshStateIds.deviceIdHashHex(deviceA), MeshStateIds.deviceIdHashHex(deviceB)), hexes)
    }

    @Test
    fun onIdentityRecordedFiresOnlyForNewDeviceIdHashes() {
        val registry = VerifiedIdentityRegistry()
        val hash = byteArrayOf(1, 2, 3, 4, 5, 6)
        val recorded = mutableListOf<ByteArray>()
        registry.onIdentityRecorded = { recorded.add(it) }

        registry.record(hash)
        registry.record(hash) // duplicado, no debe volver a disparar el callback

        assertEquals(1, recorded.size)
        assertTrue(hash.contentEquals(recorded.first()))
    }
}
