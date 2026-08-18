package com.farosos.codec

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Compara `FragmentoFirmaPacketCodec` (Caso A, `Versión=0x01`, `Tipo=3`)
 * contra `spec/test-vectors.json`, clave `fragmento_firma` (#38/#44/#45) —
 * mismo principio que `CaseBVectorLoadingTest.kt`: fuente de verdad
 * compartida con iOS, generada independientemente del codec de ninguna
 * plataforma.
 */
class FragmentoFirmaVectorTest {
    private fun fragmentoFirma(): JSONObject = TestVectorFile.load().getJSONObject("fragmento_firma")

    private fun hexToBytes(hex: String): ByteArray = TestVectorFile.hexToBytes(hex)

    private data class Vector(val name: String, val fields: JSONObject, val bytesHex: String)

    private fun fragmentVectors(): List<Vector> {
        val array = fragmentoFirma().getJSONArray("fragments")
        return (0 until array.length()).map { i ->
            val vector = array.getJSONObject(i)
            Vector(vector.getString("name"), vector.getJSONObject("fields"), vector.getString("bytes_hex"))
        }
    }

    private fun packetFrom(fields: JSONObject): FragmentoFirmaPacket = FragmentoFirmaPacket(
        deviceIdHash = hexToBytes(fields.getString("device_id_hash")),
        ttl = fields.getInt("ttl"),
        fragmentIndex = fields.getInt("frag_index"),
        fragmentCount = fields.getInt("frag_count"),
        chunk = hexToBytes(fields.getString("chunk_hex"))
    )

    @Test
    fun sharedVectorsFileDeclaresExpectedShape() {
        val vectors = fragmentoFirma()
        assertEquals(1, vectors.getInt("version"))
        assertEquals(3, vectors.getInt("message_type"))
        assertEquals(FragmentoFirmaPacket.PACKET_SIZE, vectors.getInt("packet_size_bytes"))
        assertEquals(FragmentoFirmaPacket.PAYLOAD_CHUNK_SIZE, vectors.getInt("payload_chunk_size_bytes"))
        assertEquals(7, vectors.getInt("fragment_count"))
        assertEquals(7, fragmentVectors().size)
    }

    @Test
    fun decodeMatchesEveryVectorField() {
        for ((name, fields, bytesHex) in fragmentVectors()) {
            val decoded = FragmentoFirmaPacketCodec.decode(hexToBytes(bytesHex))
            assertNotNull(decoded, "decode devolvió null para el vector $name")
            assertEquals(packetFrom(fields), decoded, name)
        }
    }

    @Test
    fun encodeMatchesEveryVectorBytes() {
        for ((name, fields, bytesHex) in fragmentVectors()) {
            val encoded = FragmentoFirmaPacketCodec.encode(packetFrom(fields))
            assertEquals(bytesHex, TestVectorFile.bytesToHex(encoded), name)
        }
    }

    @Test
    fun lastFragmentHasSixRealBytesRestHaveFifteen() {
        for ((_, fields, _) in fragmentVectors()) {
            val expectedLength = if (fields.getInt("frag_index") == 6) 6 else 15
            assertEquals(expectedLength, fields.getInt("chunk_len"))
        }
    }

    @Test
    fun decodeRejectsWrongPacketSize() {
        val bytesHex = fragmentVectors().first().bytesHex
        val truncated = hexToBytes(bytesHex).copyOfRange(0, FragmentoFirmaPacket.PACKET_SIZE - 1)
        assertNull(FragmentoFirmaPacketCodec.decode(truncated))
    }

    @Test
    fun decodeRejectsWrongMessageType() {
        val bytesHex = fragmentVectors().first().bytesHex
        val tampered = hexToBytes(bytesHex)
        tampered[2] = 0 // BEACON, no FRAGMENTO_FIRMA
        assertNull(FragmentoFirmaPacketCodec.decode(tampered))
    }

    @Test
    fun decodeRejectsIndexGreaterOrEqualToCount() {
        val bytesHex = fragmentVectors().first().bytesHex
        val tampered = hexToBytes(bytesHex)
        tampered[10] = FragmentoFirmaPacketCodec.fragHeader(7, 7) // índice fuera de rango
        assertNull(FragmentoFirmaPacketCodec.decode(tampered))
    }
}
