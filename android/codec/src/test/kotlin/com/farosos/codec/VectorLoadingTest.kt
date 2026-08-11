package com.farosos.codec

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Compara el codec de `BeaconPacketCodec` contra `spec/test-vectors.json`,
 * la fuente de verdad compartida con el codec de iOS (generada de forma
 * independiente con `struct` de Python, no derivada del código de ninguna
 * plataforma). Un round-trip aislado por plataforma no alcanza — esto valida
 * el contrato de bytes exacto que Swift y Kotlin deben compartir.
 */
class VectorLoadingTest {
    private fun repoRootDir(): File {
        // Gradle corre los tests del módulo `:codec` con working dir = android/codec/
        return File(System.getProperty("user.dir"), "../..").canonicalFile
    }

    private fun loadVectorsJson(): JSONObject {
        val vectorsFile = File(repoRootDir(), "spec/test-vectors.json")
        return JSONObject(vectorsFile.readText())
    }

    private data class Vector(val name: String, val fields: JSONObject, val bytesHex: String)

    private fun vectors(): List<Vector> {
        val array = loadVectorsJson().getJSONArray("vectors")
        return (0 until array.length()).map { i ->
            val vector = array.getJSONObject(i)
            Vector(
                name = vector.getString("name"),
                fields = vector.getJSONObject("fields"),
                bytesHex = vector.getString("bytes_hex")
            )
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexByte(hex: String): Int =
        hex.removePrefix("0x").toInt(16)

    /** Construye el `BeaconPacket` esperado a partir del `fields` de un vector
     * — única fuente de la conversión JSON -> tipos, compartida por el test
     * de decode (comparación por equals) y el de encode. */
    private fun packetFrom(fields: JSONObject): BeaconPacket {
        val messageType = BeaconPacket.MessageType.entries.first {
            it.wireValue == fields.getInt("message_type")
        }
        val status = BeaconPacket.Status.entries.first {
            it.wireValue == fields.getInt("status")
        }
        return BeaconPacket(
            messageType = messageType,
            deviceIdHash = hexToBytes(fields.getString("device_id_hash")),
            status = status,
            latitudeE7 = fields.getInt("latitude_e7"),
            longitudeE7 = fields.getInt("longitude_e7"),
            timestamp = fields.getLong("timestamp"),
            ttl = fields.getInt("ttl"),
            nonce = fields.getInt("nonce"),
            sequence = fields.getInt("sequence")
        )
    }

    @Test
    fun sharedVectorsFileIsReadableAndNonEmpty() {
        val json = loadVectorsJson()

        assertEquals("little-endian", json.getString("byte_order"))
        assertEquals(BeaconPacket.PACKET_SIZE, json.getInt("packet_size_bytes"))

        val vectors = json.getJSONArray("vectors")
        assertTrue(vectors.length() > 0)
    }

    @Test
    fun decodeMatchesEveryVectorField() {
        for ((name, fields, bytesHex) in vectors()) {
            // magic/versión no son propiedades de BeaconPacket (son constantes
            // fijas del protocolo), así que se comparan aparte contra `fields`.
            assertEquals(BeaconPacket.MAGIC, hexByte(fields.getString("magic")), name)
            assertEquals(BeaconPacket.VERSION, fields.getInt("version"), name)

            val decoded = BeaconPacketCodec.decode(hexToBytes(bytesHex))
            assertNotNull(decoded, "decode devolvió null para el vector $name")

            val expected = packetFrom(fields)
            assertEquals(expected, decoded, name)
        }
    }

    @Test
    fun encodeMatchesEveryVectorBytes() {
        for ((name, fields, bytesHex) in vectors()) {
            val expected = packetFrom(fields)
            val encoded = BeaconPacketCodec.encode(expected)

            assertEquals(bytesHex, bytesToHex(encoded), name)
        }
    }

    @Test
    fun decodeRejectsWrongMagicByte() {
        val bytesHex = vectors().first().bytesHex
        val tampered = hexToBytes(bytesHex)
        tampered[0] = 0x00 // magic incorrecto, resto del paquete intacto

        assertEquals(null, BeaconPacketCodec.decode(tampered))
    }

    @Test
    fun decodeRejectsUnknownMessageType() {
        val bytesHex = vectors().first().bytesHex
        val tampered = hexToBytes(bytesHex)
        tampered[2] = 0x7F // tipo de mensaje fuera de los valores 0..2 definidos

        assertEquals(null, BeaconPacketCodec.decode(tampered))
    }
}
