package com.farosos.personstate

import com.farosos.codec.BeaconPacket
import kotlin.test.Test
import kotlin.test.assertEquals

class PersonStateMachineTest {
    @Test
    fun simulateEarthquakeTransitionsFromDormidoToActivoSinConfirmar() {
        val scheduler = FakeScheduler()
        val machine = PersonStateMachine(scheduler, shakeDuration = 10.0, confirmationWindow = 100.0)

        machine.simulateEarthquake()

        assertEquals(PersonState.ACTIVO_SIN_CONFIRMAR, machine.state)
        assertEquals(1, machine.sequence)
    }

    @Test
    fun shakeEndingTransitionsToEsperandoConfirmacion() {
        val scheduler = FakeScheduler()
        val machine = PersonStateMachine(scheduler, shakeDuration = 10.0, confirmationWindow = 100.0)

        machine.simulateEarthquake()
        scheduler.advance(10.0)

        assertEquals(PersonState.ESPERANDO_CONFIRMACION, machine.state)
        assertEquals(BeaconPacket.Status.SIN_CONFIRMAR, machine.status)
        assertEquals(2, machine.sequence)
    }

    @Test
    fun confirmOkFromEsperandoConfirmacion() {
        val scheduler = FakeScheduler()
        val machine = PersonStateMachine(scheduler, shakeDuration = 10.0, confirmationWindow = 100.0)
        machine.simulateEarthquake()
        scheduler.advance(10.0)

        machine.confirmOk()

        assertEquals(PersonState.CONFIRMADO_OK, machine.state)
        assertEquals(BeaconPacket.Status.OK, machine.status)
        assertEquals(3, machine.sequence)
    }

    @Test
    fun requestHelpFromEsperandoConfirmacion() {
        val scheduler = FakeScheduler()
        val machine = PersonStateMachine(scheduler, shakeDuration = 10.0, confirmationWindow = 100.0)
        machine.simulateEarthquake()
        scheduler.advance(10.0)

        machine.requestHelp()

        assertEquals(PersonState.AYUDA_SOLICITADA, machine.state)
        assertEquals(BeaconPacket.Status.AYUDA, machine.status)
        assertEquals(3, machine.sequence)
    }

    @Test
    fun timeoutFiresAutomaticallyWithoutResponse() {
        val scheduler = FakeScheduler()
        val machine = PersonStateMachine(scheduler, shakeDuration = 10.0, confirmationWindow = 20.0)
        machine.simulateEarthquake()
        scheduler.advance(10.0)

        scheduler.advance(20.0)

        assertEquals(PersonState.SILENCIO_TIMEOUT, machine.state)
        assertEquals(BeaconPacket.Status.SILENCIO_TIMEOUT, machine.status)
        assertEquals(3, machine.sequence)
    }

    @Test
    fun confirmingBeforeTimeoutCancelsIt() {
        val scheduler = FakeScheduler()
        val machine = PersonStateMachine(scheduler, shakeDuration = 10.0, confirmationWindow = 20.0)
        machine.simulateEarthquake()
        scheduler.advance(10.0)
        machine.confirmOk()

        scheduler.advance(1000.0) // el timeout ya cancelado no debe disparar

        assertEquals(PersonState.CONFIRMADO_OK, machine.state)
        assertEquals(3, machine.sequence)
    }

    @Test
    fun lateRecoveryFromSilencioTimeout() {
        val scheduler = FakeScheduler()
        val machine = PersonStateMachine(scheduler, shakeDuration = 10.0, confirmationWindow = 20.0)
        machine.simulateEarthquake()
        scheduler.advance(10.0)
        scheduler.advance(20.0) // dispara el timeout -> silencioTimeout
        scheduler.advance(99999.0) // "más tarde", el usuario abre la app después

        machine.confirmOk()

        assertEquals(PersonState.CONFIRMADO_OK, machine.state)
        assertEquals(BeaconPacket.Status.OK, machine.status)
        assertEquals(4, machine.sequence)
    }

    @Test
    fun cancelHelpFromAyudaSolicitada() {
        val scheduler = FakeScheduler()
        val machine = PersonStateMachine(scheduler, shakeDuration = 10.0, confirmationWindow = 20.0)
        machine.simulateEarthquake()
        scheduler.advance(10.0)
        machine.requestHelp()

        machine.confirmOk()

        assertEquals(PersonState.CONFIRMADO_OK, machine.state)
        assertEquals(BeaconPacket.Status.OK, machine.status)
        assertEquals(4, machine.sequence)
    }

    @Test
    fun simulateEarthquakeIgnoredWhenNotDormido() {
        val scheduler = FakeScheduler()
        val machine = PersonStateMachine(scheduler, shakeDuration = 10.0, confirmationWindow = 20.0)
        machine.simulateEarthquake()
        val sequenceAfterFirst = machine.sequence

        machine.simulateEarthquake() // ya no está dormido, debe ser no-op

        assertEquals(PersonState.ACTIVO_SIN_CONFIRMAR, machine.state)
        assertEquals(sequenceAfterFirst, machine.sequence)
    }

    @Test
    fun requestHelpIgnoredOutsideEsperandoConfirmacion() {
        val scheduler = FakeScheduler()
        val machine = PersonStateMachine(scheduler, shakeDuration = 10.0, confirmationWindow = 20.0)

        machine.requestHelp() // dormido, debe ser no-op

        assertEquals(PersonState.DORMIDO, machine.state)
        assertEquals(0, machine.sequence)
    }

    @Test
    fun onTransitionFiresForManualAndAutomaticTransitions() {
        val scheduler = FakeScheduler()
        val machine = PersonStateMachine(scheduler, shakeDuration = 10.0, confirmationWindow = 20.0)
        val observed = mutableListOf<PersonState>()
        machine.onTransition = { observed.add(it) }

        machine.simulateEarthquake() // manual
        scheduler.advance(10.0) // automática: fin del sacudón
        scheduler.advance(20.0) // automática: timeout

        assertEquals(
            listOf(PersonState.ACTIVO_SIN_CONFIRMAR, PersonState.ESPERANDO_CONFIRMACION, PersonState.SILENCIO_TIMEOUT),
            observed
        )
    }
}
