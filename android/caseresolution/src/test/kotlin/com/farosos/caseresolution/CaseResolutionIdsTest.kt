package com.farosos.caseresolution

import kotlin.test.Test
import kotlin.test.assertEquals

class CaseResolutionIdsTest {
    @Test
    fun deviceIdHashHexEncodesBytesAsLowercaseHex() {
        val hash = byteArrayOf(0xAB.toByte(), 0x01, 0xFF.toByte())

        assertEquals("ab01ff", CaseResolutionIds.deviceIdHashHex(hash))
    }

    @Test
    fun meshStateDocIdConcatenatesHashHexAndSequence() {
        val hash = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9a.toByte(), 0xbc.toByte())

        assertEquals("123456789abc_7", CaseResolutionIds.meshStateDocId(hash, sequence = 7))
    }
}
