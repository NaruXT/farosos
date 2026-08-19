package com.farosos.deviceidentity

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `difficultyBits` bajo en la mayoría de los tests (no el default de
 * producción, 20) para mantener el suite rápido — `solve` es fuerza bruta,
 * el costo real solo importa en la ejecución de la app, no acá.
 */
class ProofOfWorkTest {
    private val deviceIdHash = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06)

    private fun repoRootDir(): File {
        // Gradle corre los tests del módulo `:deviceidentity` con working dir = android/deviceidentity/
        return File(System.getProperty("user.dir"), "../..").canonicalFile
    }

    private fun loadVectorsJson(): JSONObject {
        val vectorsFile = File(repoRootDir(), "spec/test-vectors.json")
        return JSONObject(vectorsFile.readText())
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    @Test
    fun leadingZeroBitsAllZeroBytes() {
        assertEquals(16, ProofOfWork.leadingZeroBits(byteArrayOf(0x00, 0x00)))
    }

    @Test
    fun leadingZeroBitsFirstByteNonZero() {
        assertEquals(2, ProofOfWork.leadingZeroBits(byteArrayOf(0b00100000)))
    }

    @Test
    fun leadingZeroBitsSkipsLeadingZeroBytes() {
        assertEquals(15, ProofOfWork.leadingZeroBits(byteArrayOf(0x00, 0b00000001)))
    }

    @Test
    fun solveProducesASealThatIsValid() {
        val nonce = ProofOfWork.solve(deviceIdHash, difficultyBits = 8)
        assertTrue(ProofOfWork.isValid(deviceIdHash, nonce, difficultyBits = 8))
    }

    @Test
    fun solveIsDeterministicForTheSameInput() {
        val first = ProofOfWork.solve(deviceIdHash, difficultyBits = 8)
        val second = ProofOfWork.solve(deviceIdHash, difficultyBits = 8)
        assertEquals(first.toList(), second.toList())
    }

    @Test
    fun isValidRejectsATamperedNonce() {
        val nonce = ProofOfWork.solve(deviceIdHash, difficultyBits = 8)
        val tampered = nonce.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xFF).toByte()
        assertFalse(ProofOfWork.isValid(deviceIdHash, tampered, difficultyBits = 8))
    }

    @Test
    fun isValidRejectsASealComputedForADifferentDeviceIdHash() {
        val otherHash = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F)
        val nonce = ProofOfWork.solve(deviceIdHash, difficultyBits = 8)
        assertFalse(ProofOfWork.isValid(otherHash, nonce, difficultyBits = 8))
    }

    @Test
    fun defaultDifficultyIsTwentyBits() {
        assertEquals(20, ProofOfWork.DIFFICULTY_BITS)
    }

    @Test
    fun solveAtDefaultDifficultyProducesAValidSeal() {
        val nonce = ProofOfWork.solve(deviceIdHash)
        assertTrue(ProofOfWork.isValid(deviceIdHash, nonce))
    }

    /**
     * Vectores compartidos con iOS (`spec/test-vectors.json`, `pow_vectors`)
     * — #51 exige que un sello calculado en una plataforma se verifique
     * correctamente en la otra. Mismo principio que
     * `DeviceIdentityHashVectorTest`/`VectorLoadingTest`.
     */
    @Test
    fun isValidAcceptsEveryPowVector() {
        val vectors = loadVectorsJson().getJSONArray("pow_vectors")
        assertTrue(vectors.length() > 0)

        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            val name = vector.getString("name")
            val hash = hexToBytes(vector.getString("device_id_hash_hex"))
            val difficultyBits = vector.getInt("difficulty_bits")
            val nonce = hexToBytes(vector.getString("nonce_hex"))
            val expectedDigest = vector.getString("expected_digest_hex")

            assertTrue(ProofOfWork.isValid(hash, nonce, difficultyBits), name)
            assertEquals(expectedDigest, sha256Hex(hash + nonce), name)
        }
    }

    private fun sha256Hex(input: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(input)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
