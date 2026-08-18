package com.farosos.deviceidentity

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compara `DeviceIdentityHash.fromPublicKey` contra `device_id_hash_vectors`
 * de `spec/test-vectors.json` (#39) — mismo principio que
 * `codec/VectorLoadingTest.kt`: la fuente de verdad vive en un archivo
 * compartido con iOS, generado independientemente del codec de ninguna
 * plataforma.
 */
class DeviceIdentityHashVectorTest {
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

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun deviceIdHashMatchesEveryVector() {
        val vectors = loadVectorsJson().getJSONArray("device_id_hash_vectors")
        assertTrue(vectors.length() > 0)

        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            val name = vector.getString("name")
            val publicKey = hexToBytes(vector.getString("public_key_ed25519_hex"))
            val expected = vector.getString("device_id_hash")

            assertEquals(expected, bytesToHex(DeviceIdentityHash.fromPublicKey(publicKey)), name)
        }
    }

    @Test
    fun deviceIdHashIsSixBytes() {
        val vectors = loadVectorsJson().getJSONArray("device_id_hash_vectors")
        val publicKey = hexToBytes(vectors.getJSONObject(0).getString("public_key_ed25519_hex"))

        assertEquals(6, DeviceIdentityHash.fromPublicKey(publicKey).size)
    }
}
