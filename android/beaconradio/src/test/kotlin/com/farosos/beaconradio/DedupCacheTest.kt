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

    private fun macKey(deviceByte: Int = 1, mac: ByteArray = byteArrayOf(1, 2, 3, 4)): DedupCache.Key =
        DedupCache.Key.forMac(deviceIdHash = byteArrayOf(deviceByte.toByte(), 0, 0, 0, 0, 0), mac = mac)

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

    // Caso B (`Versión=0x02`, clave `DeviceIdHash + MAC`, #39/#43)

    @Test
    fun sameMacForSameDeviceIsADuplicate() {
        val cache = DedupCache()
        val k = macKey()

        assertTrue(cache.insertIfAbsent(k))
        assertFalse(cache.insertIfAbsent(k), "el mismo beacon Caso B rebotado por varios relays debe verse como duplicado")
    }

    @Test
    fun differentMacForSameDeviceIsNotADuplicate() {
        val cache = DedupCache()
        assertTrue(cache.insertIfAbsent(macKey(1, byteArrayOf(1, 2, 3, 4))))
        assertTrue(
            cache.insertIfAbsent(macKey(1, byteArrayOf(1, 2, 3, 5))),
            "un beacon con contenido distinto (Timestamp avanzado) cambia el MAC y debe verse como nuevo"
        )
    }

    @Test
    fun macKeyAndNonceKeyWithSameDeviceNeverCollide() {
        val cache = DedupCache()
        // Nonce=0x0201 (LE: 01 02) vs MAC=[1,2,3,4] — ambas empiezan igual en
        // los primeros 2 bytes, pero no deben confundirse entre sí.
        assertTrue(cache.insertIfAbsent(DedupCache.Key(deviceIdHash = byteArrayOf(1, 0, 0, 0, 0, 0), nonce = 0x0201)))
        assertTrue(cache.insertIfAbsent(macKey(1, byteArrayOf(1, 2, 3, 4))))
    }
}
