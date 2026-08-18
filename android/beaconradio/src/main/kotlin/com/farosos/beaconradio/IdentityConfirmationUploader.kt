package com.farosos.beaconradio

/**
 * Sube el `VerifiedIdentityRegistry` (Caso A) al backend de agregación
 * mientras el teléfono esté en `GATEWAY_ACTIVO` (ticket #53) — mismo molde
 * que `GatewayUploader`: sube el snapshot completo ya conocido al arrancar
 * y se suscribe a identidades nuevas mientras está activo.
 */
class IdentityConfirmationUploader(
    private val registry: VerifiedIdentityRegistry,
    private val uploader: IdentityConfirmationUploading
) {
    var onError: ((Throwable) -> Unit)? = null

    /**
     * Sube el snapshot completo ya conocido y se suscribe a identidades
     * nuevas — mismo camino de código (`upload`) para ambos casos.
     */
    fun start() {
        registry.onIdentityRecorded = { deviceIdHash -> upload(deviceIdHash) }
        registry.allDeviceIdHashes().forEach { upload(it) }
    }

    /** Dejar de escuchar identidades nuevas — no vuelve a subir nada hasta el próximo `start()`. */
    fun stop() {
        registry.onIdentityRecorded = null
    }

    private fun upload(deviceIdHash: ByteArray) {
        uploader.upload(deviceIdHash) { result ->
            result.onFailure { error -> onError?.invoke(error) }
        }
    }
}
