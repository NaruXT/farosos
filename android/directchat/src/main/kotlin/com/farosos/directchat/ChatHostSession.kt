package com.farosos.directchat

/**
 * Lado que hostea el canal de chat directo (la víctima, #61) — sostiene el
 * historial completo y decide si acepta una conexión nueva. No sabe nada de
 * BLE real ni de cifrado: el "primitivo nativo" (`BluetoothGattServer` real,
 * intercambio de clave efímera, cifrado en tránsito) vive en la capa de app
 * e inyecta/orquesta este tipo — mismo principio que el resto del proyecto
 * (`ResolutionUploadCoordinator`, `GatewayUploader`, etc.): la lógica de
 * negocio se testea sin el radio real.
 *
 * El historial vive en texto plano acá (en memoria) — la capa de app es
 * responsable de persistirlo en `EncryptedSharedPreferences` (mismo
 * mecanismo que `CaseResolutionStore`) y de re-cifrarlo con la clave
 * efímera de cada conexión nueva antes de transmitirlo (#61: sin clave de
 * canal persistente compartida entre rescatistas).
 */
class ChatHostSession(initialHistory: List<ChatMessage> = emptyList()) {
    /** Se dispara cada vez que se agrega un mensaje nuevo — nunca en `acceptConnection`/`connectionClosed`. */
    var onHistoryChanged: ((List<ChatMessage>) -> Unit)? = null

    private val history = initialHistory.toMutableList()
    private var connected = false

    val isConnected: Boolean get() = connected

    /**
     * `false` si ya hay una conexión activa — la capa de app debe rechazar
     * la conexión BLE entrante en ese caso (AC de #63: una sola conexión a
     * la vez, decisión explícita por costo de batería en el teléfono de la
     * víctima, ver la sesión de `/grilling` de #61).
     */
    fun acceptConnection(): Boolean {
        if (connected) return false
        connected = true
        return true
    }

    /** Libera el canal para que el próximo rescatista pueda conectarse. */
    fun connectionClosed() {
        connected = false
    }

    fun historySnapshot(): List<ChatMessage> = history.toList()

    fun receiveMessage(text: String, sentAtEpochSeconds: Long) {
        appendMessage(ChatMessage(fromVictim = false, text = text, sentAtEpochSeconds = sentAtEpochSeconds))
    }

    fun sendMessage(text: String, sentAtEpochSeconds: Long) {
        appendMessage(ChatMessage(fromVictim = true, text = text, sentAtEpochSeconds = sentAtEpochSeconds))
    }

    private fun appendMessage(message: ChatMessage) {
        history.add(message)
        onHistoryChanged?.invoke(history.toList())
    }
}
