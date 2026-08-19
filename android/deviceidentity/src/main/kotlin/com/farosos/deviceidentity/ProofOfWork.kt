package com.farosos.deviceidentity

import java.security.MessageDigest

/**
 * Mitigación Sybil de Caso A — hashcash clásico sobre `deviceIdHash`: busca
 * un nonce de 8 bytes tal que `SHA-256(deviceIdHash || nonce)` tenga al
 * menos `DIFFICULTY_BITS` ceros a la izquierda. Se calcula una única vez al
 * instalar (decisión previa a #50/#51, 2026-08-18): `DIFFICULTY_BITS = 20`
 * da ~1s de cómputo en un teléfono moderno y hasta ~10s en gama baja —
 * suficiente para encarecer fabricar identidades falsas en lote sin
 * castigar la instalación legítima. No defiende contra un atacante con
 * GPU/hardware dedicado (mismo tipo de límite aceptado que el resto de la
 * autenticación de Caso A, ver `spec/packet-format.md`). Mismo esquema que
 * `ProofOfWork` de iOS (#50) — nonce de 8 bytes big-endian, mismo orden de
 * concatenación, mismo conteo de bits — verificado contra los mismos
 * vectores de `spec/test-vectors.json` (`pow_vectors`) para que un sello
 * calculado en una plataforma se verifique correctamente en la otra (#51).
 */
object ProofOfWork {
    const val DIFFICULTY_BITS = 20

    fun solve(deviceIdHash: ByteArray, difficultyBits: Int = DIFFICULTY_BITS): ByteArray {
        var counter = 0L
        while (true) {
            val nonce = nonceBytes(counter)
            if (leadingZeroBits(hash(deviceIdHash, nonce)) >= difficultyBits) {
                return nonce
            }
            counter += 1
        }
    }

    fun isValid(deviceIdHash: ByteArray, nonce: ByteArray, difficultyBits: Int = DIFFICULTY_BITS): Boolean =
        leadingZeroBits(hash(deviceIdHash, nonce)) >= difficultyBits

    private fun hash(deviceIdHash: ByteArray, nonce: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(deviceIdHash + nonce)

    private fun nonceBytes(counter: Long): ByteArray {
        val bytes = ByteArray(8)
        for (i in 0 until 8) {
            bytes[i] = ((counter shr (8 * (7 - i))) and 0xFF).toByte()
        }
        return bytes
    }

    internal fun leadingZeroBits(digest: ByteArray): Int {
        var count = 0
        for (byte in digest) {
            val unsigned = byte.toInt() and 0xFF
            if (unsigned == 0) {
                count += 8
                continue
            }
            count += Integer.numberOfLeadingZeros(unsigned) - 24
            break
        }
        return count
    }
}
