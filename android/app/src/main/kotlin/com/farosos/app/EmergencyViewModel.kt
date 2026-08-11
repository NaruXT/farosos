package com.farosos.app

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.farosos.personstate.PersonState
import com.farosos.personstate.PersonStateMachine

data class LogEntry(val timestampMillis: Long, val state: PersonState, val sequence: Int)

/**
 * Limitación conocida: el estado vive solo en memoria dentro de esta
 * instancia. La recuperación tardía desde `SILENCIO_TIMEOUT` funciona
 * mientras el proceso siga vivo (probado en el emulador), pero no sobrevive
 * a que el sistema mate el proceso — persistir el estado entre lanzamientos
 * queda fuera de esta ticket; es una decisión de diseño propia (dónde
 * guardarlo, cómo re-armar los timers en curso) que merece su propio issue.
 */
class EmergencyViewModel(
    private val shakeDuration: Double = 3.0,
    private val confirmationWindow: Double = 20.0
) : ViewModel() {
    var state by mutableStateOf(PersonState.DORMIDO)
        private set
    val logEntries = mutableStateListOf<LogEntry>()
    var countdownSecondsRemaining by mutableStateOf<Int?>(null)
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val machine = PersonStateMachine(RealScheduler(), shakeDuration, confirmationWindow)
    private var countdownDeadlineMillis: Long? = null
    private var countdownRunnable: Runnable? = null

    init {
        appendLogEntry()
        machine.onTransition = { newState -> handleTransition(newState) }
    }

    fun simulateEarthquake() = machine.simulateEarthquake()
    fun confirmOk() = machine.confirmOk()
    fun requestHelp() = machine.requestHelp()

    private fun handleTransition(newState: PersonState) {
        state = newState
        appendLogEntry()

        when (newState) {
            PersonState.ACTIVO_SIN_CONFIRMAR -> startCountdown(shakeDuration)
            PersonState.ESPERANDO_CONFIRMACION -> startCountdown(confirmationWindow)
            else -> stopCountdown()
        }
    }

    private fun appendLogEntry() {
        logEntries.add(LogEntry(System.currentTimeMillis(), machine.state, machine.sequence))
    }

    private fun startCountdown(durationSeconds: Double) {
        stopCountdown()
        countdownDeadlineMillis = System.currentTimeMillis() + (durationSeconds * 1000).toLong()
        countdownSecondsRemaining = durationSeconds.toInt()

        val runnable = object : Runnable {
            override fun run() {
                tickCountdown()
                if (countdownRunnable === this) {
                    handler.postDelayed(this, 1000)
                }
            }
        }
        countdownRunnable = runnable
        handler.postDelayed(runnable, 1000)
    }

    private fun tickCountdown() {
        val deadline = countdownDeadlineMillis ?: return
        val remainingMillis = deadline - System.currentTimeMillis()
        val remaining = ((remainingMillis + 999) / 1000).toInt()
        countdownSecondsRemaining = maxOf(remaining, 0)
        if (remaining <= 0) stopCountdown()
    }

    private fun stopCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
        countdownDeadlineMillis = null
        countdownSecondsRemaining = null
    }

    override fun onCleared() {
        stopCountdown()
        super.onCleared()
    }
}
