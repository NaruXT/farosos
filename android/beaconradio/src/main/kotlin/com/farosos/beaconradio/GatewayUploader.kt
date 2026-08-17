package com.farosos.beaconradio

/**
 * Sube el `MeshStateRegistry` al backend de agregación mientras el teléfono
 * esté en `GATEWAY_ACTIVO` (ticket #32, ADR-0002). Clase concreta, sin
 * interfaz propia — mismo molde que `BatteryMonitor`/`ConnectivityMonitor`
 * (callback de error + `start()`/`stop()`). El "primitivo nativo" (Firebase
 * real) queda detrás de `MeshStateUploading`, con la implementación real
 * inyectada desde la capa de app — así los tests mockean ese primitivo en
 * vez de sustituir esta clase.
 */
class GatewayUploader(
    private val registry: MeshStateRegistry,
    private val uploader: MeshStateUploading
) {
    var onError: ((Throwable) -> Unit)? = null

    /**
     * Sube el snapshot completo ya conocido y se suscribe a actualizaciones
     * incrementales — mismo camino de código (`upload`) para ambos casos.
     */
    fun start() {
        registry.onStateUpdated = { state -> upload(state) }
        registry.allStates().forEach { upload(it) }
    }

    /** Dejar de escuchar actualizaciones — no vuelve a subir nada hasta el próximo `start()`. */
    fun stop() {
        registry.onStateUpdated = null
    }

    private fun upload(state: MeshParticipantState) {
        uploader.upsert(state) { result ->
            result.onFailure { error -> onError?.invoke(error) }
        }
    }
}
