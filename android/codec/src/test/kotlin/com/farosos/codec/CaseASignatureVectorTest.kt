package com.farosos.codec

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Compara `CaseASignature` (autocertificado de Caso A) contra
 * `spec/test-vectors.json`, clave `fragmento_firma.identity` (#38/#44/#45).
 */
class CaseASignatureVectorTest {
    private fun identity(): JSONObject = TestVectorFile.load().getJSONObject("fragmento_firma").getJSONObject("identity")

    private fun hexToBytes(hex: String): ByteArray = TestVectorFile.hexToBytes(hex)

    /**
     * A diferencia de CryptoKit en iOS (firma Ed25519 "hedged", con nonce
     * aleatorio por firma — ver `project_farosos_beacon_auth_case_a_signature`,
     * memoria de sesión), `Ed25519Signer` de BouncyCastle sigue la firma
     * EdDSA determinística de RFC 8032: dos firmas sobre el mismo mensaje
     * con la misma clave SÍ son iguales byte a byte, y coinciden con
     * `signature_hex` (generado con `@noble/curves`, también determinístico
     * por RFC 8032). Por eso este test compara igualdad exacta — en iOS eso
     * no es posible y el test equivalente verifica solo `verify(sign(...))`.
     */
    @Test
    fun signMatchesTheVectorSignatureExactly() {
        val identity = identity()
        val seed = hexToBytes(identity.getString("device_secret_key_ed25519_hex"))
        val expectedSignature = identity.getString("signature_hex")

        val privateKey = Ed25519PrivateKeyParameters(seed, 0)
        assertEquals(expectedSignature, TestVectorFile.bytesToHex(CaseASignature.sign(privateKey)))
    }

    @Test
    fun signIsDeterministicAcrossCalls() {
        val identity = identity()
        val seed = hexToBytes(identity.getString("device_secret_key_ed25519_hex"))
        val privateKey = Ed25519PrivateKeyParameters(seed, 0)

        val first = CaseASignature.sign(privateKey)
        val second = CaseASignature.sign(privateKey)
        assertTrue(first.contentEquals(second))
    }

    @Test
    fun verifyAcceptsTheVectorSignature() {
        val identity = identity()
        val publicKey = hexToBytes(identity.getString("device_public_key_ed25519_hex"))
        val signature = hexToBytes(identity.getString("signature_hex"))

        assertTrue(CaseASignature.verify(publicKey, signature))
    }

    @Test
    fun verifyRejectsATamperedSignature() {
        val identity = identity()
        val publicKey = hexToBytes(identity.getString("device_public_key_ed25519_hex"))
        val tampered = hexToBytes(identity.getString("signature_hex"))
        tampered[0] = (tampered[0].toInt() xor 0xFF).toByte()

        assertFalse(CaseASignature.verify(publicKey, tampered))
    }

    @Test
    fun verifyRejectsASignatureFromADifferentIdentity() {
        val identity = identity()
        val publicKey = hexToBytes(identity.getString("device_public_key_ed25519_hex"))

        val otherSeed = ByteArray(32) { 0x42 }
        val otherPrivateKey = Ed25519PrivateKeyParameters(otherSeed, 0)
        val otherSignature = CaseASignature.sign(otherPrivateKey)

        assertFalse(CaseASignature.verify(publicKey, otherSignature))
    }
}
