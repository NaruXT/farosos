import XCTest
@testable import NetworkRoleMachine

final class NetworkRoleMachineTests: XCTestCase {
    func testStartsInApagado() {
        let machine = NetworkRoleMachine()

        XCTAssertEqual(machine.state, .apagado)
    }

    func testAppActivatedTransitionsFromApagadoToSoloRetransmite() {
        let machine = NetworkRoleMachine()

        machine.appActivated()

        XCTAssertEqual(machine.state, .soloRetransmite)
    }

    func testAppActivatedIsNoOpWhenNotApagado() {
        let machine = NetworkRoleMachine()
        machine.appActivated()

        machine.appActivated() // ya no está apagado, debe ser no-op

        XCTAssertEqual(machine.state, .soloRetransmite)
    }

    func testConnectivityDetectedTransitionsFromSoloRetransmiteToGatewayActivo() {
        let machine = NetworkRoleMachine()
        machine.appActivated()

        machine.connectivityDetected()

        XCTAssertEqual(machine.state, .gatewayActivo)
    }

    func testConnectivityDetectedIgnoredOutsideSoloRetransmite() {
        let machine = NetworkRoleMachine()

        machine.connectivityDetected() // sigue apagado, debe ser no-op

        XCTAssertEqual(machine.state, .apagado)
    }

    func testNothingPendingTransitionsFromGatewayActivoToSincronizadoIdle() {
        let machine = NetworkRoleMachine()
        machine.appActivated()
        machine.connectivityDetected()

        machine.nothingPendingToSync()

        XCTAssertEqual(machine.state, .sincronizadoIdle)
    }

    func testNothingPendingIgnoredOutsideGatewayActivo() {
        let machine = NetworkRoleMachine()
        machine.appActivated()

        machine.nothingPendingToSync() // en soloRetransmite, debe ser no-op

        XCTAssertEqual(machine.state, .soloRetransmite)
    }

    func testSomethingPendingTransitionsFromSincronizadoIdleToGatewayActivo() {
        let machine = NetworkRoleMachine()
        machine.appActivated()
        machine.connectivityDetected()
        machine.nothingPendingToSync()

        machine.somethingPendingToSync()

        XCTAssertEqual(machine.state, .gatewayActivo)
    }

    func testSomethingPendingIgnoredOutsideSincronizadoIdle() {
        let machine = NetworkRoleMachine()
        machine.appActivated()

        machine.somethingPendingToSync() // en soloRetransmite, debe ser no-op

        XCTAssertEqual(machine.state, .soloRetransmite)
    }

    func testLowBatteryTransitionsToBajoConsumoFromSoloRetransmite() {
        let machine = NetworkRoleMachine()
        machine.appActivated()

        machine.updateBattery(percent: 10, isCharging: false)

        XCTAssertEqual(machine.state, .bajoConsumo)
    }

    func testLowBatteryTransitionsToBajoConsumoFromGatewayActivo() {
        let machine = NetworkRoleMachine()
        machine.appActivated()
        machine.connectivityDetected()

        machine.updateBattery(percent: 10, isCharging: false)

        XCTAssertEqual(machine.state, .bajoConsumo)
    }

    func testLowBatteryTransitionsToBajoConsumoFromSincronizadoIdle() {
        let machine = NetworkRoleMachine()
        machine.appActivated()
        machine.connectivityDetected()
        machine.nothingPendingToSync()

        machine.updateBattery(percent: 5, isCharging: false)

        XCTAssertEqual(machine.state, .bajoConsumo)
    }

    func testLowBatteryTransitionsToBajoConsumoFromApagado() {
        let machine = NetworkRoleMachine()

        machine.updateBattery(percent: 5, isCharging: false)

        XCTAssertEqual(machine.state, .bajoConsumo)
    }

    func testBatteryAboveThresholdRecoversFromBajoConsumoToSoloRetransmite() {
        let machine = NetworkRoleMachine()
        machine.appActivated()
        machine.updateBattery(percent: 10, isCharging: false)

        machine.updateBattery(percent: 30, isCharging: false)

        XCTAssertEqual(machine.state, .soloRetransmite)
    }

    func testChargingRecoversFromBajoConsumoRegardlessOfPercent() {
        let machine = NetworkRoleMachine()
        machine.appActivated()
        machine.updateBattery(percent: 10, isCharging: false)

        // Todavía por debajo del umbral de batería baja (15%), pero
        // cargando — debe recuperar igual, sin esperar a superar 25%.
        machine.updateBattery(percent: 5, isCharging: true)

        XCTAssertEqual(machine.state, .soloRetransmite)
    }

    func testBatteryUpdateBelowRecoveryThresholdStaysInBajoConsumo() {
        let machine = NetworkRoleMachine()
        machine.appActivated()
        machine.updateBattery(percent: 10, isCharging: false)

        machine.updateBattery(percent: 20, isCharging: false) // ni <15 ni >25, ni cargando

        XCTAssertEqual(machine.state, .bajoConsumo)
    }

    func testBatteryUpdateIgnoredWhenNormalAndNotLow() {
        let machine = NetworkRoleMachine()
        machine.appActivated()

        machine.updateBattery(percent: 80, isCharging: false)

        XCTAssertEqual(machine.state, .soloRetransmite)
    }

    func testBatteryUpdateWhileAlreadyLowDoesNotRefireTransition() {
        let machine = NetworkRoleMachine()
        machine.appActivated()
        machine.updateBattery(percent: 10, isCharging: false)
        var observed: [NetworkRole] = []
        machine.onTransition = { observed.append($0) }

        machine.updateBattery(percent: 8, isCharging: false) // sigue bajo, ya está en bajoConsumo

        XCTAssertEqual(machine.state, .bajoConsumo)
        XCTAssertEqual(observed, [])
    }

    func testOnTransitionFiresForEachTransition() {
        let machine = NetworkRoleMachine()
        var observed: [NetworkRole] = []
        machine.onTransition = { observed.append($0) }

        machine.appActivated()
        machine.connectivityDetected()
        machine.nothingPendingToSync()
        machine.updateBattery(percent: 10, isCharging: false)
        machine.updateBattery(percent: 30, isCharging: false)

        XCTAssertEqual(observed, [.soloRetransmite, .gatewayActivo, .sincronizadoIdle, .bajoConsumo, .soloRetransmite])
    }
}
