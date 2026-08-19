package com.farosos.directchat

import java.util.Base64

/**
 * Codificación de una lista de [ChatMessage] a una sola cadena y de vuelta
 * — usada tanto para lo que viaja por BLE (una notificación GATT puede
 * llevar el historial completo la primera vez, o un solo mensaje después,
 * #61/#63) como para lo que se persiste en disco (`ChatHistoryStore`),
 * fuente única en vez de reimplementar el mismo formato en dos lugares.
 *
 * El texto de cada mensaje va en Base64 dentro de su registro: es el único
 * campo de largo variable que puede contener el separador de campo/registro
 * si el usuario escribe un "|" o un salto de línea — Base64 lo vuelve
 * imposible sin tener que escapar nada.
 */
object ChatMessageWireFormat {
    private const val FIELD_SEPARATOR = "|"
    private const val RECORD_SEPARATOR = "\n"

    fun encode(messages: List<ChatMessage>): String =
        messages.joinToString(RECORD_SEPARATOR, transform = ::encodeOne)

    fun decode(raw: String): List<ChatMessage> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(RECORD_SEPARATOR).mapNotNull(::decodeOne)
    }

    private fun encodeOne(message: ChatMessage): String = listOf(
        if (message.fromVictim) "1" else "0",
        message.sentAtEpochSeconds,
        Base64.getEncoder().encodeToString(message.text.toByteArray(Charsets.UTF_8))
    ).joinToString(FIELD_SEPARATOR)

    private fun decodeOne(raw: String): ChatMessage? = runCatching {
        val parts = raw.split(FIELD_SEPARATOR)
        ChatMessage(
            fromVictim = parts[0] == "1",
            sentAtEpochSeconds = parts[1].toLong(),
            text = String(Base64.getDecoder().decode(parts[2]), Charsets.UTF_8)
        )
    }.getOrNull()
}
