package com.farosos.directchat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatHostSessionTest {
    @Test
    fun `acceptConnection acepta la primera conexión`() {
        val session = ChatHostSession()

        assertTrue(session.acceptConnection())
        assertTrue(session.isConnected)
    }

    @Test
    fun `acceptConnection rechaza una segunda conexión mientras la primera sigue activa (#63)`() {
        val session = ChatHostSession()
        session.acceptConnection()

        assertFalse(session.acceptConnection(), "no debe aceptar dos rescatistas conectados a la vez")
    }

    @Test
    fun `connectionClosed libera el canal para que otro rescatista se pueda conectar después`() {
        val session = ChatHostSession()
        session.acceptConnection()

        session.connectionClosed()

        assertFalse(session.isConnected)
        assertTrue(session.acceptConnection(), "una vez cerrada la conexión anterior, debe aceptar una nueva")
    }

    @Test
    fun `historySnapshot arranca vacío sin historial previo`() {
        val session = ChatHostSession()

        assertEquals(emptyList(), session.historySnapshot())
    }

    @Test
    fun `historySnapshot incluye el historial pasado al construir la sesión`() {
        val previous = listOf(ChatMessage(fromVictim = true, text = "hola", sentAtEpochSeconds = 1))
        val session = ChatHostSession(initialHistory = previous)

        assertEquals(previous, session.historySnapshot())
    }

    @Test
    fun `receiveMessage agrega un mensaje del rescatista al historial`() {
        val session = ChatHostSession()

        session.receiveMessage("voy hacia el norte", 100)

        assertEquals(listOf(ChatMessage(fromVictim = false, "voy hacia el norte", 100)), session.historySnapshot())
    }

    @Test
    fun `sendMessage agrega un mensaje de la víctima al historial`() {
        val session = ChatHostSession()

        session.sendMessage("estoy consciente", 200)

        assertEquals(listOf(ChatMessage(fromVictim = true, "estoy consciente", 200)), session.historySnapshot())
    }

    @Test
    fun `los mensajes se acumulan en el orden en que se escriben, sin importar quién los manda`() {
        val session = ChatHostSession()

        session.receiveMessage("¿cómo estás?", 1)
        session.sendMessage("bien, atrapada pero consciente", 2)
        session.receiveMessage("ya casi llego", 3)

        assertEquals(
            listOf(
                ChatMessage(false, "¿cómo estás?", 1),
                ChatMessage(true, "bien, atrapada pero consciente", 2),
                ChatMessage(false, "ya casi llego", 3)
            ),
            session.historySnapshot()
        )
    }

    @Test
    fun `un rescatista que se conecta después ve el historial completo de rescatistas anteriores (#61)`() {
        val session = ChatHostSession()
        session.acceptConnection()
        session.receiveMessage("acá Pedro, voy a entrar por el norte", 1)
        session.connectionClosed()

        session.acceptConnection()

        assertEquals(1, session.historySnapshot().size, "Luis debe ver lo que Pedro ya escribió al conectarse")
    }

    @Test
    fun `onHistoryChanged se dispara solo cuando se agrega un mensaje nuevo`() {
        val session = ChatHostSession()
        var callCount = 0
        session.onHistoryChanged = { callCount++ }

        session.sendMessage("mensaje 1", 1)
        session.receiveMessage("mensaje 2", 2)

        assertEquals(2, callCount)
    }

    @Test
    fun `historySnapshot devuelve una copia, no la lista interna mutable`() {
        val session = ChatHostSession()
        session.sendMessage("uno", 1)

        val snapshot = session.historySnapshot()
        session.sendMessage("dos", 2)

        assertEquals(1, snapshot.size, "el snapshot tomado antes no debe verse afectado por escrituras posteriores")
    }
}
