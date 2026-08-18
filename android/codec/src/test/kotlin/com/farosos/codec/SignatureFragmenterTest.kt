package com.farosos.codec

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Compara `SignatureFragmenter.fragment` contra `spec/test-vectors.json`,
 * clave `fragmento_firma` (#38/#44/#45), y verifica el round-trip
 * fragment/reassemble con datos arbitrarios.
 */
class SignatureFragmenterTest {
    private fun hexToBytes(hex: String): ByteArray = TestVectorFile.hexToBytes(hex)

    private fun fragmentoFirma(): JSONObject = TestVectorFile.load().getJSONObject("fragmento_firma")

    @Test
    fun fragmentMatchesEveryVectorFragment() {
        val vectors = fragmentoFirma()
        val identity = vectors.getJSONObject("identity")
        val publicKey = hexToBytes(identity.getString("device_public_key_ed25519_hex"))
        val signature = hexToBytes(identity.getString("signature_hex"))
        val deviceIdHash = hexToBytes(identity.getString("device_id_hash"))
        val fragmentArray = vectors.getJSONArray("fragments")
        val ttl = fragmentArray.getJSONObject(0).getJSONObject("fields").getInt("ttl")

        val fragments = SignatureFragmenter.fragment(publicKey, signature, deviceIdHash, ttl)
        assertEquals(7, fragments.size)

        for (i in 0 until fragmentArray.length()) {
            val vector = fragmentArray.getJSONObject(i)
            val fields = vector.getJSONObject("fields")
            val index = fields.getInt("frag_index")
            val expected = FragmentoFirmaPacket(
                deviceIdHash = deviceIdHash,
                ttl = ttl,
                fragmentIndex = index,
                fragmentCount = fields.getInt("frag_count"),
                chunk = hexToBytes(fields.getString("chunk_hex"))
            )
            assertEquals(expected, fragments[index], vector.getString("name"))
        }
    }

    @Test
    fun reassembleFromVectorFragmentsRecoversTheOriginalPayload() {
        val vectors = fragmentoFirma()
        val expectedPayload = hexToBytes(vectors.getString("payload_hex"))
        val identity = vectors.getJSONObject("identity")
        val deviceIdHash = hexToBytes(identity.getString("device_id_hash"))
        val fragmentArray = vectors.getJSONArray("fragments")

        val fragments = (0 until fragmentArray.length()).map { i ->
            val fields = fragmentArray.getJSONObject(i).getJSONObject("fields")
            FragmentoFirmaPacket(
                deviceIdHash = deviceIdHash,
                ttl = fields.getInt("ttl"),
                fragmentIndex = fields.getInt("frag_index"),
                fragmentCount = fields.getInt("frag_count"),
                chunk = hexToBytes(fields.getString("chunk_hex"))
            )
        }

        assertNotNull(SignatureFragmenter.reassemble(fragments))
        assertTrue(expectedPayload.contentEquals(SignatureFragmenter.reassemble(fragments)))
    }

    @Test
    fun reassembleWorksRegardlessOfFragmentOrder() {
        val publicKey = ByteArray(32) { it.toByte() }
        val signature = ByteArray(64) { (255 - it).toByte() }
        val deviceIdHash = byteArrayOf(1, 2, 3, 4, 5, 6)

        val fragments = SignatureFragmenter.fragment(publicKey, signature, deviceIdHash, 16).shuffled()

        assertTrue((publicKey + signature).contentEquals(SignatureFragmenter.reassemble(fragments)))
    }

    @Test
    fun reassembleReturnsNullWhenFragmentsAreMissing() {
        val publicKey = ByteArray(32) { 0xAA.toByte() }
        val signature = ByteArray(64) { 0xBB.toByte() }
        val deviceIdHash = byteArrayOf(1, 2, 3, 4, 5, 6)

        val fragments = SignatureFragmenter.fragment(publicKey, signature, deviceIdHash, 16)
        assertNull(SignatureFragmenter.reassemble(fragments.dropLast(1)))
    }

    @Test
    fun reassembleReturnsNullWhenTwoFragmentsWithTheSameIndexDisagree() {
        val publicKey = ByteArray(32) { 0xAA.toByte() }
        val signature = ByteArray(64) { 0xBB.toByte() }
        val deviceIdHash = byteArrayOf(1, 2, 3, 4, 5, 6)

        val fragments = SignatureFragmenter.fragment(publicKey, signature, deviceIdHash, 16).toMutableList()
        val tampered = fragments[0].copy(chunk = ByteArray(fragments[0].chunk.size) { 0xFF.toByte() })
        fragments.add(tampered) // mismo índice que fragments[0], contenido distinto

        assertNull(SignatureFragmenter.reassemble(fragments))
    }

    @Test
    fun reassembleReturnsNullForEmptyInput() {
        assertNull(SignatureFragmenter.reassemble(emptyList()))
    }

    @Test
    fun splitIsTheInverseOfConcatenatingPublicKeyAndSignature() {
        val vectors = fragmentoFirma()
        val identity = vectors.getJSONObject("identity")
        val publicKey = hexToBytes(identity.getString("device_public_key_ed25519_hex"))
        val signature = hexToBytes(identity.getString("signature_hex"))
        val payload = hexToBytes(vectors.getString("payload_hex"))

        val split = SignatureFragmenter.split(payload)
        assertNotNull(split)
        assertTrue(publicKey.contentEquals(split.first))
        assertTrue(signature.contentEquals(split.second))
    }

    @Test
    fun splitReturnsNullForAPayloadOfTheWrongSize() {
        assertNull(SignatureFragmenter.split(ByteArray(95)))
        assertNull(SignatureFragmenter.split(ByteArray(97)))
    }
}
