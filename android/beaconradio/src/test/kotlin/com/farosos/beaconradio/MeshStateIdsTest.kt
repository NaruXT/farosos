package com.farosos.beaconradio

import kotlin.test.Test
import kotlin.test.assertEquals

class MeshStateIdsTest {
    @Test
    fun deviceIdHashHexEncodesBytesAsLowercaseHex() {
        val hash = byteArrayOf(0xAB.toByte(), 0x01, 0xFF.toByte())

        assertEquals("ab01ff", MeshStateIds.deviceIdHashHex(hash))
    }

    @Test
    fun docIdCombinesHashHexAndSequenceWithUnderscore() {
        val hash = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9a.toByte(), 0xbc.toByte())

        assertEquals("123456789abc_7", MeshStateIds.docId(deviceIdHash = hash, sequence = 7))
    }
}
