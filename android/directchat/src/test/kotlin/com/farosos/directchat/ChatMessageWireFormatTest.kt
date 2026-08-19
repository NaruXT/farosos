package com.farosos.directchat

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatMessageWireFormatTest {
    @Test
    fun `encode de una lista vacía y decode de vuelta da lista vacía`() {
        assertEquals(emptyList(), ChatMessageWireFormat.decode(ChatMessageWireFormat.encode(emptyList())))
    }

    @Test
    fun `round-trip de un solo mensaje`() {
        val messages = listOf(ChatMessage(fromVictim = true, text = "estoy consciente", sentAtEpochSeconds = 100))

        assertEquals(messages, ChatMessageWireFormat.decode(ChatMessageWireFormat.encode(messages)))
    }

    @Test
    fun `round-trip de varios mensajes conserva el orden`() {
        val messages = listOf(
            ChatMessage(false, "¿cómo estás?", 1),
            ChatMessage(true, "bien, consciente", 2),
            ChatMessage(false, "ya casi llego", 3)
        )

        assertEquals(messages, ChatMessageWireFormat.decode(ChatMessageWireFormat.encode(messages)))
    }

    @Test
    fun `un mensaje con salto de línea y separador de campo no corrompe el formato`() {
        val messages = listOf(
            ChatMessage(true, "línea 1\nlínea 2 | con barra", 1),
            ChatMessage(false, "siguiente mensaje normal", 2)
        )

        assertEquals(messages, ChatMessageWireFormat.decode(ChatMessageWireFormat.encode(messages)))
    }

    @Test
    fun `decode de un registro corrupto se descarta sin reventar, el resto se conserva`() {
        val valid = ChatMessageWireFormat.encode(listOf(ChatMessage(true, "ok", 1)))
        val raw = "esto no tiene el formato esperado\n$valid"

        assertEquals(listOf(ChatMessage(true, "ok", 1)), ChatMessageWireFormat.decode(raw))
    }
}
