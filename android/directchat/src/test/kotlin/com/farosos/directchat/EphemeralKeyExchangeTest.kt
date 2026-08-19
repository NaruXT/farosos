package com.farosos.directchat

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Sin vector externo hardcodeado a propósito: se intentó traer el vector
 * oficial de RFC 7748 §6.1 vía fetch, pero la herramienta de fetch pasa el
 * contenido por un modelo intermediario que no reproduce hex byte a byte de
 * forma confiable (los cuatro valores volvieron con 66 caracteres en vez de
 * 64, cada uno con un byte extra distinto) — no vale la pena arriesgar un
 * vector potencialmente incorrecto. En su lugar, se verifica la propiedad
 * que sí importa para #61: que el ECDH es simétrico y determinístico contra
 * la implementación real de BouncyCastle (misma librería ya validada byte a
 * byte para Caso B contra `spec/test-vectors.json`, ver `CaseBAuthentication`).
 */
class EphemeralKeyExchangeTest {
    @Test
    fun `generateKeyPair produce claves de 32 bytes y distintas en cada llamada`() {
        val first = EphemeralKeyExchange.generateKeyPair()
        val second = EphemeralKeyExchange.generateKeyPair()

        assertEquals(32, first.privateKey.size)
        assertEquals(32, first.publicKey.size)
        assertFalse(first.privateKey.contentEquals(second.privateKey), "cada conexión debe generar una clave nueva (#61)")
        assertFalse(first.publicKey.contentEquals(second.publicKey))
    }

    @Test
    fun `agree es simétrico entre dos pares generados por separado`() {
        val victim = EphemeralKeyExchange.generateKeyPair()
        val rescuer = EphemeralKeyExchange.generateKeyPair()

        val fromVictim = EphemeralKeyExchange.agree(victim.privateKey, rescuer.publicKey)
        val fromRescuer = EphemeralKeyExchange.agree(rescuer.privateKey, victim.publicKey)

        assertContentEquals(fromVictim, fromRescuer)
        assertEquals(32, fromVictim.size)
    }

    @Test
    fun `agree es determinístico para el mismo par de claves`() {
        val victim = EphemeralKeyExchange.generateKeyPair()
        val rescuer = EphemeralKeyExchange.generateKeyPair()

        val first = EphemeralKeyExchange.agree(victim.privateKey, rescuer.publicKey)
        val second = EphemeralKeyExchange.agree(victim.privateKey, rescuer.publicKey)

        assertContentEquals(first, second)
    }

    @Test
    fun `pares distintos producen secretos distintos`() {
        val victim = EphemeralKeyExchange.generateKeyPair()
        val rescuerA = EphemeralKeyExchange.generateKeyPair()
        val rescuerB = EphemeralKeyExchange.generateKeyPair()

        val withA = EphemeralKeyExchange.agree(victim.privateKey, rescuerA.publicKey)
        val withB = EphemeralKeyExchange.agree(victim.privateKey, rescuerB.publicKey)

        assertFalse(withA.contentEquals(withB), "cada conexión debe derivar un secreto distinto")
    }
}
