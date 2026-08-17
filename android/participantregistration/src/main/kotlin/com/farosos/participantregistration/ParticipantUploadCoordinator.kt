package com.farosos.participantregistration

/**
 * Desacopla la subida del perfil de `participants/{device_id_hash}` de
 * `GATEWAY_ACTIVO` (ADR-0003): se dispara con cualquier señal de
 * conectividad real, sin importar el rol de red del teléfono, porque el
 * registro ocurre antes de cualquier emergencia. Reintenta en la siguiente
 * señal de conectividad si la subida falla, o si no había conectividad al
 * momento del registro.
 *
 * El perfil pendiente entra únicamente por el constructor (recién guardado
 * en `EncryptedSharedPreferences` por el flujo de registro, o releído por
 * `ParticipantStore` en el siguiente arranque si quedó sin subir) — no hay
 * un setter aparte, para no tener dos caminos distintos hacia el mismo
 * estado.
 */
class ParticipantUploadCoordinator(
    private val deviceIdHash: ByteArray,
    private val uploader: ParticipantUploading,
    private var pendingProfile: ParticipantProfile? = null
) {
    var onUploadSucceeded: (() -> Unit)? = null

    private var isUploading = false

    fun connectivityDetected() {
        val profile = pendingProfile ?: return
        if (isUploading) return
        isUploading = true
        uploader.upload(deviceIdHash, profile) { result ->
            isUploading = false
            result.onSuccess {
                pendingProfile = null
                onUploadSucceeded?.invoke()
            }
            // en caso de fallo, sigue pendiente y se reintenta en la
            // próxima señal de conectividad
        }
    }
}
