package com.farosos.personstate

/**
 * `Scheduler` de prueba: el tiempo solo avanza cuando el test llama a
 * `advance(by:)`, así los tests corren instantáneo sin depender de esperar
 * minutos reales para el timer de gracia o el timeout.
 */
class FakeScheduler : Scheduler {
    private class Entry(val id: Int, val fireTime: Double, val action: () -> Unit) : SchedulerToken

    private val entries = mutableListOf<Entry>()
    private var now = 0.0
    private var nextId = 0

    override fun schedule(afterSeconds: Double, action: () -> Unit): SchedulerToken {
        nextId += 1
        val entry = Entry(nextId, now + afterSeconds, action)
        entries.add(entry)
        return entry
    }

    override fun cancel(token: SchedulerToken) {
        val entry = token as? Entry ?: return
        entries.removeAll { it.id == entry.id }
    }

    /**
     * Avanza el reloj y dispara, en orden, cualquier acción programada cuyo
     * tiempo ya se cumplió.
     */
    fun advance(by: Double) {
        now += by
        while (true) {
            val due = entries.filter { it.fireTime <= now }.minByOrNull { it.fireTime } ?: break
            entries.removeAll { it.id == due.id }
            due.action()
        }
    }
}
