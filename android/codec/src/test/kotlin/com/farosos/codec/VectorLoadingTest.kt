package com.farosos.codec

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Valida solo que el plumbing hacia `spec/test-vectors.json` funciona. Los
 * tests reales de encode/decode contra estos vectores se agregan junto con
 * la implementación de `BeaconPacketCodec` (ver issue de Fase 1) — no antes,
 * para no fingir cobertura de un codec que todavía no existe.
 */
class VectorLoadingTest {
    private fun repoRootDir(): File {
        // Gradle corre los tests del módulo `:codec` con working dir = android/codec/
        return File(System.getProperty("user.dir"), "../..").canonicalFile
    }

    @Test
    fun sharedVectorsFileIsReadableAndNonEmpty() {
        val vectorsFile = File(repoRootDir(), "spec/test-vectors.json")
        val json = JSONObject(vectorsFile.readText())

        assertEquals("little-endian", json.getString("byte_order"))
        assertEquals(BeaconPacket.PACKET_SIZE, json.getInt("packet_size_bytes"))

        val vectors = json.getJSONArray("vectors")
        assertTrue(vectors.length() > 0)
    }
}
