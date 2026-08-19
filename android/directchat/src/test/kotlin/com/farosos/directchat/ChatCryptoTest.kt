package com.farosos.directchat

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ChatCryptoTest {
    @Test
    fun `deriveSessionKey produce 32 bytes y es determinístico`() {
        val secret = ByteArray(32) { it.toByte() }
        val first = ChatCrypto.deriveSessionKey(secret)
        val second = ChatCrypto.deriveSessionKey(secret)

        assertContentEquals(first, second)
        kotlin.test.assertEquals(32, first.size)
    }

    @Test
    fun `secretos distintos derivan claves de sesión distintas`() {
        val keyA = ChatCrypto.deriveSessionKey(ByteArray(32) { 1 })
        val keyB = ChatCrypto.deriveSessionKey(ByteArray(32) { 2 })

        assertFalse(keyA.contentEquals(keyB))
    }

    @Test
    fun `encrypt seguido de decrypt recupera el texto original`() {
        val sessionKey = ChatCrypto.deriveSessionKey(ByteArray(32) { it.toByte() })
        val plaintext = "somos 3 personas acá, hay agua cerca".toByteArray(Charsets.UTF_8)

        val ciphertext = ChatCrypto.encrypt(sessionKey, plaintext)
        val recovered = ChatCrypto.decrypt(sessionKey, ciphertext)

        assertContentEquals(plaintext, recovered)
    }

    @Test
    fun `dos cifrados del mismo texto no son iguales (IV nuevo por mensaje)`() {
        val sessionKey = ChatCrypto.deriveSessionKey(ByteArray(32) { it.toByte() })
        val plaintext = "voy a entrar por el lado norte".toByteArray(Charsets.UTF_8)

        val first = ChatCrypto.encrypt(sessionKey, plaintext)
        val second = ChatCrypto.encrypt(sessionKey, plaintext)

        assertFalse(first.contentEquals(second), "cada mensaje debe usar un IV distinto, aunque el texto sea el mismo")
    }

    @Test
    fun `decrypt con la clave equivocada falla en vez de devolver basura silenciosa`() {
        val correctKey = ChatCrypto.deriveSessionKey(ByteArray(32) { 1 })
        val wrongKey = ChatCrypto.deriveSessionKey(ByteArray(32) { 2 })
        val ciphertext = ChatCrypto.encrypt(correctKey, "mensaje".toByteArray())

        assertFailsWith<Exception> { ChatCrypto.decrypt(wrongKey, ciphertext) }
    }

    @Test
    fun `decrypt de un payload corrompido falla en vez de devolver basura silenciosa`() {
        val sessionKey = ChatCrypto.deriveSessionKey(ByteArray(32) { 1 })
        val ciphertext = ChatCrypto.encrypt(sessionKey, "mensaje".toByteArray())
        ciphertext[ciphertext.size - 1] = (ciphertext[ciphertext.size - 1] + 1).toByte()

        assertFailsWith<Exception> { ChatCrypto.decrypt(sessionKey, ciphertext) }
    }
}
