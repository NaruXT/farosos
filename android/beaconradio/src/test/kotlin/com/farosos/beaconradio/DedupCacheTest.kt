package com.farosos.beaconradio

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DedupCacheTest {
    private class ManualClock {
        var current = 0L
        fun advanceSeconds(seconds: Long) {
            current += seconds * 1000
        }
    }

    private fun key(deviceByte: Int = 1, nonce: Int = 42): DedupCache.Key =
        DedupCache.Key(deviceIdHash = byteArrayOf(deviceByte.toByte(), 0, 0, 0, 0, 0), nonce = nonce)

    @Test
    fun firstInsertionIsAccepted() {
        val cache = DedupCache()
        assertTrue(cache.insertIfAbsent(key()))
    }

    @Test
    fun secondInsertionOfSameKeyIsRejectedAsDuplicate() {
        val cache = DedupCache()
        val k = key()

        assertTrue(cache.insertIfAbsent(k))
        assertFalse(cache.insertIfAbsent(k))
    }

    @Test
    fun differentNonceForSameDeviceIsNotADuplicate() {
        val cache = DedupCache()
        assertTrue(cache.insertIfAbsent(key(1, nonce = 1)))
        assertTrue(cache.insertIfAbsent(key(1, nonce = 2)))
    }

    @Test
    fun entryExpiresAfterTtl() {
        val clock = ManualClock()
        val cache = DedupCache(ttlMillis = 30 * 60 * 1000L, nowMillis = { clock.current })
        val k = key()

        assertTrue(cache.insertIfAbsent(k))
        clock.advanceSeconds(30 * 60)

        assertTrue(cache.insertIfAbsent(k), "una entrada expirada debe tratarse como nueva")
    }

    @Test
    fun entryIsStillADuplicateJustBeforeTtlExpires() {
        val clock = ManualClock()
        val cache = DedupCache(ttlMillis = 30 * 60 * 1000L, nowMillis = { clock.current })
        val k = key()

        assertTrue(cache.insertIfAbsent(k))
        clock.advanceSeconds(30 * 60 - 1)

        assertFalse(cache.insertIfAbsent(k))
    }

    @Test
    fun lruEvictsOldestEntryWhenOverCapacity() {
        val cache = DedupCache(capacity = 2)
        val keyA = key(1)
        val keyB = key(2)
        val keyC = key(3)

        assertTrue(cache.insertIfAbsent(keyA))
        assertTrue(cache.insertIfAbsent(keyB))
        assertTrue(cache.insertIfAbsent(keyC)) // desaloja keyA

        assertTrue(cache.insertIfAbsent(keyA), "keyA fue desalojada, debe tratarse como nueva")
        assertFalse(cache.insertIfAbsent(keyC), "keyC sigue vigente")
    }

    @Test
    fun touchingAnEntryProtectsItFromEviction() {
        val cache = DedupCache(capacity = 2)
        val keyA = key(1)
        val keyB = key(2)
        val keyC = key(3)

        assertTrue(cache.insertIfAbsent(keyA))
        assertTrue(cache.insertIfAbsent(keyB))
        assertFalse(cache.insertIfAbsent(keyA)) // touch: keyA vuelve a ser la más reciente
        assertTrue(cache.insertIfAbsent(keyC)) // debe desalojar keyB, no keyA

        assertFalse(cache.insertIfAbsent(keyA), "keyA fue tocada recientemente, no debió desalojarse")
        assertTrue(cache.insertIfAbsent(keyB), "keyB fue desalojada")
    }
}
