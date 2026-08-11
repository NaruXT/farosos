package com.farosos.personstate

import com.farosos.codec.BeaconPacket

/**
 * Máquina de estados A (persona). No conoce BLE ni UI — solo el estado, las
 * transiciones válidas, y el reloj inyectado para el timer de gracia y el
 * timeout. `status`/`sequence` reutilizan los tipos de `codec` (#3) para que
 * quien construya el `BeaconPacket` real (capa BLE, fuera de esta ticket) no
 * tenga que traducir un vocabulario propio.
 */
class PersonStateMachine(
    private val scheduler: Scheduler,
    private val shakeDuration: Double,
    private val confirmationWindow: Double
) {
    var state: PersonState = PersonState.DORMIDO
        private set
    var status: BeaconPacket.Status = BeaconPacket.Status.SIN_CONFIRMAR
        private set
    var sequence: Int = 0
        private set

    /**
     * Se dispara con cada transición, manual o automática (fin del sacudón,
     * timeout) — la UI lo usa para actualizar la pantalla y el log sin tener
     * que adivinar cuándo un timer disparó una transición por su cuenta.
     */
    var onTransition: ((PersonState) -> Unit)? = null

    private var pendingToken: SchedulerToken? = null

    fun simulateEarthquake() {
        if (state != PersonState.DORMIDO) return
        transition(PersonState.ACTIVO_SIN_CONFIRMAR)
        pendingToken = scheduler.schedule(shakeDuration) { shakeEnded() }
    }

    private fun shakeEnded() {
        if (state != PersonState.ACTIVO_SIN_CONFIRMAR) return
        transition(PersonState.ESPERANDO_CONFIRMACION)
        pendingToken = scheduler.schedule(confirmationWindow) { timeoutFired() }
    }

    private fun timeoutFired() {
        if (state != PersonState.ESPERANDO_CONFIRMACION) return
        transition(PersonState.SILENCIO_TIMEOUT)
    }

    fun confirmOk() {
        if (state != PersonState.ESPERANDO_CONFIRMACION &&
            state != PersonState.SILENCIO_TIMEOUT &&
            state != PersonState.AYUDA_SOLICITADA
        ) return
        cancelPending()
        transition(PersonState.CONFIRMADO_OK)
    }

    fun requestHelp() {
        if (state != PersonState.ESPERANDO_CONFIRMACION) return
        cancelPending()
        transition(PersonState.AYUDA_SOLICITADA)
    }

    private fun cancelPending() {
        pendingToken?.let { scheduler.cancel(it) }
        pendingToken = null
    }

    private fun transition(newState: PersonState) {
        state = newState
        status = newState.beaconStatus
        sequence = (sequence + 1) and 0xFF
        onTransition?.invoke(newState)
    }
}
