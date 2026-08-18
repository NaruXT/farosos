package com.farosos.beaconradio

import com.farosos.codec.CaseASignature
import com.farosos.codec.FragmentoFirmaPacket
import com.farosos.codec.SignatureFragmenter
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignatureFragmentAssemblerTest {
    private class ManualClock {
        var current = 0L
        fun advanceSeconds(seconds: Long) {
            current += seconds * 1000
        }
    }

    private fun makeIdentityFragments(deviceIdHash: ByteArray = byteArrayOf(1, 2, 3, 4, 5, 6), ttl: Int = 16): List<FragmentoFirmaPacket> {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val privateKey = generator.generateKeyPair().private as Ed25519PrivateKeyParameters
        val publicKey = privateKey.generatePublicKey().encoded
        val signature = CaseASignature.sign(privateKey)
        return SignatureFragmenter.fragment(publicKey, signature, deviceIdHash, ttl)
    }

    @Test
    fun isNotVerifiedBeforeAllFragmentsArrive() {
        val assembler = SignatureFragmentAssembler()
        val fragments = makeIdentityFragments()

        for (fragment in fragments.dropLast(1)) assembler.receive(fragment)

        assertFalse(assembler.isVerified(fragments[0].deviceIdHash))
    }

    @Test
    fun becomesVerifiedOnceAllFragmentsArriveInOrder() {
        val assembler = SignatureFragmentAssembler()
        val deviceIdHash = byteArrayOf(9, 9, 9, 9, 9, 9)
        val fragments = makeIdentityFragments(deviceIdHash = deviceIdHash)

        var reportedPublicKey: ByteArray? = null
        var reportedDeviceIdHash: ByteArray? = null
        assembler.onIdentityVerified = { hash, publicKey ->
            reportedDeviceIdHash = hash
            reportedPublicKey = publicKey
        }

        var completed = false
        for (fragment in fragments) completed = assembler.receive(fragment) || completed

        assertTrue(completed)
        assertTrue(assembler.isVerified(deviceIdHash))
        assertTrue(deviceIdHash.contentEquals(reportedDeviceIdHash))
        assertEquals(32, reportedPublicKey?.size)
    }

    @Test
    fun becomesVerifiedRegardlessOfFragmentArrivalOrder() {
        val assembler = SignatureFragmentAssembler()
        val fragments = makeIdentityFragments().shuffled()

        for (fragment in fragments) assembler.receive(fragment)

        assertTrue(assembler.isVerified(fragments[0].deviceIdHash))
    }

    @Test
    fun onIdentityVerifiedFiresOnlyOnce() {
        val assembler = SignatureFragmentAssembler()
        val fragments = makeIdentityFragments()

        var callCount = 0
        assembler.onIdentityVerified = { _, _ -> callCount++ }

        for (fragment in fragments) assembler.receive(fragment)
        // Retransmisión completa del mismo conjunto (p. ej. otro relay lo
        // reenvía de nuevo) no debe volver a disparar el callback.
        for (fragment in fragments) assembler.receive(fragment)

        assertEquals(1, callCount)
    }

    @Test
    fun tamperedFragmentNeverVerifiesEvenWithAllIndicesPresent() {
        val assembler = SignatureFragmentAssembler()
        val fragments = makeIdentityFragments().toMutableList()
        val original = fragments[3]
        val tamperedChunk = original.chunk.copyOf()
        tamperedChunk[0] = (tamperedChunk[0].toInt() xor 0xFF).toByte()
        fragments[3] = original.copy(chunk = tamperedChunk)

        for (fragment in fragments) assembler.receive(fragment)

        assertFalse(assembler.isVerified(fragments[0].deviceIdHash))
    }

    @Test
    fun twoDifferentDevicesAreTrackedIndependently() {
        val assembler = SignatureFragmentAssembler()
        val deviceA = byteArrayOf(1, 1, 1, 1, 1, 1)
        val deviceB = byteArrayOf(2, 2, 2, 2, 2, 2)
        val fragmentsA = makeIdentityFragments(deviceIdHash = deviceA)
        val fragmentsB = makeIdentityFragments(deviceIdHash = deviceB)

        for (fragment in fragmentsA.dropLast(1)) assembler.receive(fragment)
        for (fragment in fragmentsB) assembler.receive(fragment)

        assertFalse(assembler.isVerified(deviceA), "a A todavía le falta un fragmento")
        assertTrue(assembler.isVerified(deviceB))
    }

    // Memoria acotada (capacidad + TTL), mismo motivo que `DedupCache`

    @Test
    fun exceedingCapacityEvictsTheOldestIncompleteDevice() {
        val assembler = SignatureFragmentAssembler(capacity = 2)
        val deviceA = byteArrayOf(1, 1, 1, 1, 1, 1)
        val deviceB = byteArrayOf(2, 2, 2, 2, 2, 2)
        val deviceC = byteArrayOf(3, 3, 3, 3, 3, 3)
        val fragmentsA = makeIdentityFragments(deviceIdHash = deviceA)
        val fragmentsB = makeIdentityFragments(deviceIdHash = deviceB)
        val fragmentsC = makeIdentityFragments(deviceIdHash = deviceC)

        assembler.receive(fragmentsA[0])
        assembler.receive(fragmentsB[0])
        assembler.receive(fragmentsC[0]) // sobre capacidad (2) -> desaloja el progreso parcial de A

        for (fragment in fragmentsA.drop(1)) assembler.receive(fragment)
        assertFalse(assembler.isVerified(deviceA), "el progreso parcial de A se desalojó, el índice 0 se perdió")

        for (fragment in fragmentsA) assembler.receive(fragment)
        assertTrue(assembler.isVerified(deviceA), "reenviar el conjunto completo debe volver a acumularse desde cero")
    }

    @Test
    fun incompleteFragmentsExpireAfterTtl() {
        val clock = ManualClock()
        val assembler = SignatureFragmentAssembler(ttlMillis = 30 * 60 * 1000L, nowMillis = { clock.current })
        val fragments = makeIdentityFragments()

        assembler.receive(fragments[0])
        clock.advanceSeconds(30 * 60)

        for (fragment in fragments.drop(1)) assembler.receive(fragment)
        assertFalse(assembler.isVerified(fragments[0].deviceIdHash), "el fragmento 0 expiró, el conjunto sigue incompleto")
    }
}
