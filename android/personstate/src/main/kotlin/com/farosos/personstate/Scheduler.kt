package com.farosos.personstate

/**
 * Reloj/programador inyectable para que `PersonStateMachine` pueda testear
 * el timer de gracia y el timeout sin depender de hilos reales ni esperar
 * minutos reales. En producción, la app lo respalda con un
 * `Handler`/coroutine; en tests, un `Scheduler` de prueba avanza el tiempo
 * manualmente.
 */
interface SchedulerToken

interface Scheduler {
    fun schedule(afterSeconds: Double, action: () -> Unit): SchedulerToken
    fun cancel(token: SchedulerToken)
}
