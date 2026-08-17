package com.farosos.participantregistration

import kotlin.test.Test
import kotlin.test.assertEquals

class ParticipantIdsTest {
    @Test
    fun deviceIdHashHexEncodesBytesAsLowercaseHex() {
        val hash = byteArrayOf(0xAB.toByte(), 0x01, 0xFF.toByte())

        assertEquals("ab01ff", ParticipantIds.deviceIdHashHex(hash))
    }

    @Test
    fun participantDocIdMatchesDeviceIdHashHex() {
        val hash = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9a.toByte(), 0xbc.toByte())

        assertEquals(ParticipantIds.deviceIdHashHex(hash), ParticipantIds.participantDocId(hash))
    }
}
