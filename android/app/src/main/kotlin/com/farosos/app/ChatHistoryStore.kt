package com.farosos.app

import android.content.Context
import com.farosos.directchat.ChatMessage
import com.farosos.directchat.ChatMessageWireFormat

/**
 * Persiste el historial del propio canal de chat directo (#61/#63) en
 * `EncryptedSharedPreferences`, mismo mecanismo que `CaseResolutionStore`.
 * Reusa `ChatMessageWireFormat` (mismo formato que ya viaja por BLE, #61) en
 * vez de reimplementar la codificación acá — el orden de los mensajes
 * importa, por eso no se guarda como `StringSet` (Android no garantiza su
 * orden) sino como la cadena completa que ya produce ese formato. En texto
 * plano local (el cifrado de #61 protege el aire, no el disco; el disco ya
 * está protegido por `EncryptedSharedPreferences`).
 */
object ChatHistoryStore {
    private const val PREFS_FILE_NAME = "com.farosos.app.direct_chat_history"
    private const val KEY_MESSAGES = "messages"

    fun history(context: Context): List<ChatMessage> {
        val raw = prefs(context).getString(KEY_MESSAGES, null) ?: return emptyList()
        return ChatMessageWireFormat.decode(raw)
    }

    fun replaceHistory(messages: List<ChatMessage>, context: Context) {
        prefs(context).edit().putString(KEY_MESSAGES, ChatMessageWireFormat.encode(messages)).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_MESSAGES).apply()
    }

    private fun prefs(context: Context) = EncryptedPrefsStore.open(PREFS_FILE_NAME, context)
}
