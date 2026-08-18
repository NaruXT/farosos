package com.farosos.codec

import org.json.JSONObject
import java.io.File

/**
 * Utilidades compartidas para leer `spec/test-vectors.json` desde los tests
 * de `:codec` (layout legado, Caso B, autenticación) — evita repetir el
 * mismo helper de resolución de ruta + parseo de hex en cada archivo de
 * vectores.
 */
object TestVectorFile {
    fun load(): JSONObject {
        val vectorsFile = File(repoRootDir(), "spec/test-vectors.json")
        return JSONObject(vectorsFile.readText())
    }

    fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun hexByte(hex: String): Int = hex.removePrefix("0x").toInt(16)

    // Gradle corre los tests del módulo `:codec` con working dir = android/codec/
    private fun repoRootDir(): File = File(System.getProperty("user.dir"), "../..").canonicalFile
}
