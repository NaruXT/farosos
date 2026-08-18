package com.farosos.codec

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Compara `CaseBAuthentication` (ECDH + MAC de Caso B) contra
 * `spec/test-vectors.json`, claves `ecdh`/`mac_vectors` (#39/#43).
 */
class CaseBAuthenticationVectorTest {
    private fun loadVectorsJson(): JSONObject = TestVectorFile.load()

    private fun hexToBytes(hex: String): ByteArray = TestVectorFile.hexToBytes(hex)

    private fun bytesToHex(bytes: ByteArray): String = TestVectorFile.bytesToHex(bytes)

    @Test
    fun backendPublicKeyMatchesSharedConstant() {
        val ecdh = loadVectorsJson().getJSONObject("ecdh")
        val expected = hexToBytes(ecdh.getString("backend_public_key_x25519_hex"))
        assertEquals(expected.toList(), CaseBAuthentication.BACKEND_PUBLIC_KEY_X25519.toList())
    }

    @Test
    fun deriveKSharedMatchesEveryVector() {
        val ecdh = loadVectorsJson().getJSONObject("ecdh")
        val vectors = ecdh.getJSONArray("vectors")
        assertTrue(vectors.length() > 0)

        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            val name = vector.getString("name")
            val deviceSeed = hexToBytes(vector.getString("device_secret_key_ed25519_hex"))
            val backendPub = hexToBytes(vector.getString("backend_public_key_x25519_hex"))
            val expected = vector.getString("expected_k_shared_hex")

            val kShared = CaseBAuthentication.deriveKShared(deviceSeed, backendPub)
            assertEquals(expected, bytesToHex(kShared), name)
        }
    }

    @Test
    fun computeMacMatchesEveryVector() {
        val vectors = loadVectorsJson().getJSONArray("mac_vectors")
        assertTrue(vectors.length() > 0)

        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            val name = vector.getString("name")
            val kShared = hexToBytes(vector.getString("k_shared_hex"))
            val content = hexToBytes(vector.getString("content_hex"))
            val expected = vector.getString("expected_mac_hex")

            assertEquals(expected, bytesToHex(CaseBAuthentication.computeMac(kShared, content)), name)
        }
    }

    @Test
    fun authenticatedContentMatchesEveryCaseBVector() {
        val caseB = loadVectorsJson().getJSONObject("case_b")
        val vectors = caseB.getJSONArray("vectors")

        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            val name = vector.getString("name")
            val fields = vector.getJSONObject("fields")
            val expectedContentHex = vector.getString("content_hex")

            val messageType = BeaconPacket.MessageType.entries.first { it.wireValue == fields.getInt("message_type") }
            val status = BeaconPacket.Status.entries.first { it.wireValue == fields.getInt("status") }
            val packet = CaseBBeaconPacket(
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

            val content = CaseBAuthentication.authenticatedContent(packet)
            assertEquals(expectedContentHex, bytesToHex(content), name)
        }
    }

    @Test
    fun changingAnyAuthenticatedFieldChangesTheMac() {
        val kShared = ByteArray(32) { 0x11 }
        val base = CaseBBeaconPacket(
            messageType = BeaconPacket.MessageType.BEACON,
            deviceIdHash = byteArrayOf(0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte()),
            status = BeaconPacket.Status.OK,
            latitudeE7 = 194326000,
            longitudeE7 = -991332000,
            timestamp = 1700010000L,
            ttl = 16,
            mac = ByteArray(4),
            sequence = 0
        )
        val baseMac = CaseBAuthentication.computeMac(kShared, CaseBAuthentication.authenticatedContent(base))

        val changedDeviceIdHash = base.copy(
            deviceIdHash = byteArrayOf(0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0x00)
        )
        val changedStatus = base.copy(status = BeaconPacket.Status.AYUDA)
        val changedLatitude = base.copy(latitudeE7 = base.latitudeE7 + 1)
        val changedLongitude = base.copy(longitudeE7 = base.longitudeE7 + 1)
        val changedTimestamp = base.copy(timestamp = base.timestamp + 1)
        val changedTtl = base.copy(ttl = base.ttl - 1)
        val changedSequence = base.copy(sequence = base.sequence + 1)

        val variants = listOf(
            "deviceIdHash" to changedDeviceIdHash,
            "status" to changedStatus,
            "latitude" to changedLatitude,
            "longitude" to changedLongitude,
            "timestamp" to changedTimestamp,
            "ttl" to changedTtl,
            "sequence" to changedSequence
        )
        for ((name, variant) in variants) {
            val mac = CaseBAuthentication.computeMac(kShared, CaseBAuthentication.authenticatedContent(variant))
            assertNotEquals(bytesToHex(baseMac), bytesToHex(mac), "cambiar $name debería cambiar el MAC")
        }
    }
}
