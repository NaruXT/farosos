package com.farosos.codec

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Compara el codec de `CaseBBeaconPacketCodec` (Versión=0x02) contra
 * `spec/test-vectors.json`, clave `case_b` (#39/#43) — mismo principio que
 * `VectorLoadingTest.kt` para el layout legado: fuente de verdad compartida
 * con iOS, generada independientemente del codec de ninguna plataforma.
 */
class CaseBVectorLoadingTest {
    private fun loadVectorsJson(): JSONObject = TestVectorFile.load()

    private fun caseB(): JSONObject = loadVectorsJson().getJSONObject("case_b")

    private fun hexToBytes(hex: String): ByteArray = TestVectorFile.hexToBytes(hex)

    private fun bytesToHex(bytes: ByteArray): String = TestVectorFile.bytesToHex(bytes)

    private data class Vector(val name: String, val fields: JSONObject, val bytesHex: String)

    private fun vectors(): List<Vector> {
        val array = caseB().getJSONArray("vectors")
        return (0 until array.length()).map { i ->
            val vector = array.getJSONObject(i)
            Vector(vector.getString("name"), vector.getJSONObject("fields"), vector.getString("bytes_hex"))
        }
    }

    private fun packetFrom(fields: JSONObject): CaseBBeaconPacket {
        val messageType = BeaconPacket.MessageType.entries.first { it.wireValue == fields.getInt("message_type") }
        val status = BeaconPacket.Status.entries.first { it.wireValue == fields.getInt("status") }
        return CaseBBeaconPacket(
            messageType = messageType,
            deviceIdHash = hexToBytes(fields.getString("device_id_hash")),
            status = status,
            latitudeE7 = fields.getInt("latitude_e7"),
            longitudeE7 = fields.getInt("longitude_e7"),
            timestamp = fields.getLong("timestamp"),
            ttl = fields.getInt("ttl"),
            mac = hexToBytes(fields.getString("mac")),
            sequence = fields.getInt("sequence")
        )
    }

    @Test
    fun sharedVectorsFileDeclaresExpectedShape() {
        val caseB = caseB()
        assertEquals(2, caseB.getInt("version"))
        assertEquals(CaseBBeaconPacket.PACKET_SIZE, caseB.getInt("packet_size_bytes"))
        assertTrue(vectors().isNotEmpty())
    }

    @Test
    fun decodeMatchesEveryVectorField() {
        for ((name, fields, bytesHex) in vectors()) {
            val decoded = CaseBBeaconPacketCodec.decode(hexToBytes(bytesHex))
            assertNotNull(decoded, "decode devolvió null para el vector $name")
            assertEquals(packetFrom(fields), decoded, name)
        }
    }

    @Test
    fun encodeMatchesEveryVectorBytes() {
        for ((name, fields, bytesHex) in vectors()) {
            val encoded = CaseBBeaconPacketCodec.encode(packetFrom(fields))
            assertEquals(bytesHex, bytesToHex(encoded), name)
        }
    }

    @Test
    fun decodeRejectsWrongPacketSize() {
        val bytesHex = vectors().first().bytesHex
        val truncated = hexToBytes(bytesHex).copyOfRange(0, CaseBBeaconPacket.PACKET_SIZE - 1)
        assertNull(CaseBBeaconPacketCodec.decode(truncated))
    }

    @Test
    fun decodeRejectsWrongVersion() {
        val bytesHex = vectors().first().bytesHex
        val tampered = hexToBytes(bytesHex)
        tampered[1] = 0x01 // versión del layout legado, no de Caso B
        assertNull(CaseBBeaconPacketCodec.decode(tampered))
    }
}
