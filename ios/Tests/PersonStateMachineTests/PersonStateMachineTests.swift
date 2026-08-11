import XCTest
import PacketCodec
@testable import PersonStateMachine
import TestSupport

final class PersonStateMachineTests: XCTestCase {
    func testSimulateEarthquakeTransitionsFromDormidoToActivoSinConfirmar() {
        let scheduler = FakeScheduler()
        let machine = PersonStateMachine(scheduler: scheduler, shakeDuration: 10, confirmationWindow: 100)

        machine.simulateEarthquake()

        XCTAssertEqual(machine.state, .activoSinConfirmar)
        XCTAssertEqual(machine.sequence, 1)
    }

    func testShakeEndingTransitionsToEsperandoConfirmacion() {
        let scheduler = FakeScheduler()
        let machine = PersonStateMachine(scheduler: scheduler, shakeDuration: 10, confirmationWindow: 100)

        machine.simulateEarthquake()
        scheduler.advance(by: 10)

        XCTAssertEqual(machine.state, .esperandoConfirmacion)
        XCTAssertEqual(machine.status, .sinConfirmar)
        XCTAssertEqual(machine.sequence, 2)
    }

    func testConfirmOkFromEsperandoConfirmacion() {
        let scheduler = FakeScheduler()
        let machine = PersonStateMachine(scheduler: scheduler, shakeDuration: 10, confirmationWindow: 100)
        machine.simulateEarthquake()
        scheduler.advance(by: 10)

        machine.confirmOk()

        XCTAssertEqual(machine.state, .confirmadoOk)
        XCTAssertEqual(machine.status, .ok)
        XCTAssertEqual(machine.sequence, 3)
    }

    func testRequestHelpFromEsperandoConfirmacion() {
        let scheduler = FakeScheduler()
        let machine = PersonStateMachine(scheduler: scheduler, shakeDuration: 10, confirmationWindow: 100)
        machine.simulateEarthquake()
        scheduler.advance(by: 10)

        machine.requestHelp()

        XCTAssertEqual(machine.state, .ayudaSolicitada)
        XCTAssertEqual(machine.status, .ayuda)
        XCTAssertEqual(machine.sequence, 3)
    }

    func testTimeoutFiresAutomaticallyWithoutResponse() {
        let scheduler = FakeScheduler()
        let machine = PersonStateMachine(scheduler: scheduler, shakeDuration: 10, confirmationWindow: 20)
        machine.simulateEarthquake()
        scheduler.advance(by: 10)

        scheduler.advance(by: 20)

        XCTAssertEqual(machine.state, .silencioTimeout)
        XCTAssertEqual(machine.status, .silencioTimeout)
        XCTAssertEqual(machine.sequence, 3)
    }

    func testConfirmingBeforeTimeoutCancelsIt() {
        let scheduler = FakeScheduler()
        let machine = PersonStateMachine(scheduler: scheduler, shakeDuration: 10, confirmationWindow: 20)
        machine.simulateEarthquake()
        scheduler.advance(by: 10)
        machine.confirmOk()

        scheduler.advance(by: 1000) // el timeout ya cancelado no debe disparar

        XCTAssertEqual(machine.state, .confirmadoOk)
        XCTAssertEqual(machine.sequence, 3)
    }

    func testLateRecoveryFromSilencioTimeout() {
        let scheduler = FakeScheduler()
        let machine = PersonStateMachine(scheduler: scheduler, shakeDuration: 10, confirmationWindow: 20)
        machine.simulateEarthquake()
        scheduler.advance(by: 10)
        scheduler.advance(by: 20) // dispara el timeout -> silencioTimeout
        scheduler.advance(by: 99999) // "más tarde", el usuario abre la app después

        machine.confirmOk()

        XCTAssertEqual(machine.state, .confirmadoOk)
        XCTAssertEqual(machine.status, .ok)
        XCTAssertEqual(machine.sequence, 4)
    }

    func testCancelHelpFromAyudaSolicitada() {
        let scheduler = FakeScheduler()
        let machine = PersonStateMachine(scheduler: scheduler, shakeDuration: 10, confirmationWindow: 20)
        machine.simulateEarthquake()
        scheduler.advance(by: 10)
        machine.requestHelp()

        machine.confirmOk()

        XCTAssertEqual(machine.state, .confirmadoOk)
        XCTAssertEqual(machine.status, .ok)
        XCTAssertEqual(machine.sequence, 4)
    }

    func testSimulateEarthquakeIgnoredWhenNotDormido() {
        let scheduler = FakeScheduler()
        let machine = PersonStateMachine(scheduler: scheduler, shakeDuration: 10, confirmationWindow: 20)
        machine.simulateEarthquake()
        let sequenceAfterFirst = machine.sequence

        machine.simulateEarthquake() // ya no está dormido, debe ser no-op

        XCTAssertEqual(machine.state, .activoSinConfirmar)
        XCTAssertEqual(machine.sequence, sequenceAfterFirst)
    }

    func testRequestHelpIgnoredOutsideEsperandoConfirmacion() {
        let scheduler = FakeScheduler()
        let machine = PersonStateMachine(scheduler: scheduler, shakeDuration: 10, confirmationWindow: 20)

        machine.requestHelp() // dormido, debe ser no-op

        XCTAssertEqual(machine.state, .dormido)
        XCTAssertEqual(machine.sequence, 0)
    }

    func testOnTransitionFiresForManualAndAutomaticTransitions() {
        let scheduler = FakeScheduler()
        let machine = PersonStateMachine(scheduler: scheduler, shakeDuration: 10, confirmationWindow: 20)
        var observed: [PersonState] = []
        machine.onTransition = { observed.append($0) }

        machine.simulateEarthquake() // manual
        scheduler.advance(by: 10) // automática: fin del sacudón
        scheduler.advance(by: 20) // automática: timeout

        XCTAssertEqual(observed, [.activoSinConfirmar, .esperandoConfirmacion, .silencioTimeout])
    }
}
