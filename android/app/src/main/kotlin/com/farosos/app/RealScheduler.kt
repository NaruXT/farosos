package com.farosos.app

import android.os.Handler
import android.os.Looper
import com.farosos.personstate.Scheduler
import com.farosos.personstate.SchedulerToken

/**
 * `Scheduler` de producción, respaldado por el `Handler` del main looper. El
 * `PersonStateMachine` en sí no depende de Android — solo esta capa de app,
 * para poder mantener el módulo `:personstate` testeado libre de esa
 * dependencia.
 */
class RealScheduler : Scheduler {
    private class Entry(val runnable: Runnable) : SchedulerToken

    private val handler = Handler(Looper.getMainLooper())

    override fun schedule(afterSeconds: Double, action: () -> Unit): SchedulerToken {
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, (afterSeconds * 1000).toLong())
        return Entry(runnable)
    }

    override fun cancel(token: SchedulerToken) {
        (token as? Entry)?.let { handler.removeCallbacks(it.runnable) }
    }
}
