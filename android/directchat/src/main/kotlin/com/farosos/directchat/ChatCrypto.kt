package com.farosos.directchat

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cifrado de los mensajes del chat en tránsito (#61), sobre la clave de
 * sesión derivada del secreto ECDH efímero de [EphemeralKeyExchange]. Usa
 * `javax.crypto` nativo de la plataforma (AES/GCM), no BouncyCastle — a
 * diferencia de X25519, AES-GCM sí está soportado de forma confiable en
 * `java.security` desde API 1, sin el problema de compatibilidad que motivó
 * BouncyCastle para Ed25519/X25519 (#41).
 */
object ChatCrypto {
    private const val AES_KEY_ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    /**
     * Deriva una clave AES-256 del secreto ECDH crudo. SHA-256 alcanza como
     * función de derivación de un solo paso: el secreto ya tiene alta
     * entropía y se usa una sola vez por conexión, sin contexto adicional
     * que mezclar (a diferencia de un HKDF con info/salt, que resolvería un
     * problema — reusar el mismo secreto para propósitos distintos — que
     * acá no existe).
     */
    fun deriveSessionKey(sharedSecret: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(sharedSecret)

    /** `IV (12 bytes) || ciphertext+tag`. Un IV nuevo por mensaje — nunca se reusa el mismo IV con la misma clave. */
    fun encrypt(sessionKey: ByteArray, plaintext: ByteArray, secureRandom: SecureRandom = SecureRandom()): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, AES_KEY_ALGORITHM), GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    /** Lanza si el payload está corrompido, truncado, o la clave no coincide — nunca devuelve basura silenciosa. */
    fun decrypt(sessionKey: ByteArray, payload: ByteArray): ByteArray {
        require(payload.size > GCM_IV_BYTES) { "payload demasiado corto para contener IV + ciphertext" }
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, AES_KEY_ALGORITHM), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
