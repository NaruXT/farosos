package com.farosos.networkrole

/**
 * Máquina de estados B (rol de red del teléfono, `spec/packet-format.md`,
 * issue #12). Corre en paralelo a `PersonStateMachine` — un teléfono puede
 * estar en cualquier combinación de ambas, no se combinan en un único enum.
 *
 * Sin `Scheduler`: a diferencia de la Máquina A, ninguna transición depende
 * de un timer — todas llegan como señales externas (app activa,
 * conectividad, nada pendiente, lectura de batería), inyectadas por quien
 * use esta clase.
 */
class NetworkRoleMachine {
    companion object {
        /** Por debajo de este porcentaje, el teléfono entra a `BAJO_CONSUMO`. */
        private const val LOW_BATTERY_THRESHOLD = 15

        /** Por encima de este porcentaje (o cargando), sale de `BAJO_CONSUMO`. */
        private const val RECOVERY_THRESHOLD = 25
    }

    var state: NetworkRole = NetworkRole.APAGADO
        private set

    /** Se dispara con cada transición — la UI lo usa para el log en pantalla. */
    var onTransition: ((NetworkRole) -> Unit)? = null

    /**
     * La app pasa a primer plano/se activa — único disparador de salida de
     * `APAGADO`. No requiere confirmación explícita del usuario, a
     * diferencia de la Máquina A.
     */
    fun appActivated() {
        if (state != NetworkRole.APAGADO) return
        transition(NetworkRole.SOLO_RETRANSMITE)
    }

    /** El teléfono detecta conectividad real hacia internet (no solo BLE). */
    fun connectivityDetected() {
        if (state != NetworkRole.SOLO_RETRANSMITE) return
        transition(NetworkRole.GATEWAY_ACTIVO)
    }

    /** No queda ningún beacon ajeno pendiente de agregación/anuncio. */
    fun nothingPendingToSync() {
        if (state != NetworkRole.GATEWAY_ACTIVO) return
        transition(NetworkRole.SINCRONIZADO_IDLE)
    }

    /**
     * Volvió a quedar algo pendiente (p. ej. llegó un beacon ajeno nuevo)
     * mientras el teléfono estaba tranquilo en `SINCRONIZADO_IDLE` — único
     * camino de vuelta a `GATEWAY_ACTIVO`, sin el cual la máquina se
     * quedaba atascada ignorando información nueva.
     */
    fun somethingPendingToSync() {
        if (state != NetworkRole.SINCRONIZADO_IDLE) return
        transition(NetworkRole.GATEWAY_ACTIVO)
    }

    /**
     * Se llama con cada lectura de batería. Si ya está en `BAJO_CONSUMO`,
     * solo evalúa la condición de recuperación (batería > 25% O cargando —
     * cargando recupera sin importar el porcentaje, por eso se revisa antes
     * que nada, no como un `else` del umbral bajo). Si no está en
     * `BAJO_CONSUMO`, evalúa si debe entrar por batería < 15%.
     */
    fun updateBattery(percent: Int, isCharging: Boolean) {
        if (state == NetworkRole.BAJO_CONSUMO) {
            if (percent > RECOVERY_THRESHOLD || isCharging) {
                transition(NetworkRole.SOLO_RETRANSMITE)
            }
            return
        }
        if (percent < LOW_BATTERY_THRESHOLD) {
            transition(NetworkRole.BAJO_CONSUMO)
        }
    }

    private fun transition(newState: NetworkRole) {
        state = newState
        onTransition?.invoke(newState)
    }
}
