package com.farosos.networkrole

import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkRoleMachineTest {
    @Test
    fun startsInApagado() {
        val machine = NetworkRoleMachine()

        assertEquals(NetworkRole.APAGADO, machine.state)
    }

    @Test
    fun appActivatedTransitionsFromApagadoToSoloRetransmite() {
        val machine = NetworkRoleMachine()

        machine.appActivated()

        assertEquals(NetworkRole.SOLO_RETRANSMITE, machine.state)
    }

    @Test
    fun appActivatedIsNoOpWhenNotApagado() {
        val machine = NetworkRoleMachine()
        machine.appActivated()

        machine.appActivated() // ya no está apagado, debe ser no-op

        assertEquals(NetworkRole.SOLO_RETRANSMITE, machine.state)
    }

    @Test
    fun connectivityDetectedTransitionsFromSoloRetransmiteToGatewayActivo() {
        val machine = NetworkRoleMachine()
        machine.appActivated()

        machine.connectivityDetected()

        assertEquals(NetworkRole.GATEWAY_ACTIVO, machine.state)
    }

    @Test
    fun connectivityDetectedIgnoredOutsideSoloRetransmite() {
        val machine = NetworkRoleMachine()

        machine.connectivityDetected() // sigue apagado, debe ser no-op

        assertEquals(NetworkRole.APAGADO, machine.state)
    }

    @Test
    fun nothingPendingTransitionsFromGatewayActivoToSincronizadoIdle() {
        val machine = NetworkRoleMachine()
        machine.appActivated()
        machine.connectivityDetected()

        machine.nothingPendingToSync()

        assertEquals(NetworkRole.SINCRONIZADO_IDLE, machine.state)
    }

    @Test
    fun nothingPendingIgnoredOutsideGatewayActivo() {
        val machine = NetworkRoleMachine()
        machine.appActivated()

        machine.nothingPendingToSync() // en soloRetransmite, debe ser no-op

        assertEquals(NetworkRole.SOLO_RETRANSMITE, machine.state)
    }

    @Test
    fun lowBatteryTransitionsToBajoConsumoFromSoloRetransmite() {
        val machine = NetworkRoleMachine()
        machine.appActivated()

        machine.updateBattery(percent = 10, isCharging = false)

        assertEquals(NetworkRole.BAJO_CONSUMO, machine.state)
    }

    @Test
    fun lowBatteryTransitionsToBajoConsumoFromGatewayActivo() {
        val machine = NetworkRoleMachine()
        machine.appActivated()
        machine.connectivityDetected()

        machine.updateBattery(percent = 10, isCharging = false)

        assertEquals(NetworkRole.BAJO_CONSUMO, machine.state)
    }

    @Test
    fun lowBatteryTransitionsToBajoConsumoFromSincronizadoIdle() {
        val machine = NetworkRoleMachine()
        machine.appActivated()
        machine.connectivityDetected()
        machine.nothingPendingToSync()

        machine.updateBattery(percent = 5, isCharging = false)

        assertEquals(NetworkRole.BAJO_CONSUMO, machine.state)
    }

    @Test
    fun lowBatteryTransitionsToBajoConsumoFromApagado() {
        val machine = NetworkRoleMachine()

        machine.updateBattery(percent = 5, isCharging = false)

        assertEquals(NetworkRole.BAJO_CONSUMO, machine.state)
    }

    @Test
    fun batteryAboveThresholdRecoversFromBajoConsumoToSoloRetransmite() {
        val machine = NetworkRoleMachine()
        machine.appActivated()
        machine.updateBattery(percent = 10, isCharging = false)

        machine.updateBattery(percent = 30, isCharging = false)

        assertEquals(NetworkRole.SOLO_RETRANSMITE, machine.state)
    }

    @Test
    fun chargingRecoversFromBajoConsumoRegardlessOfPercent() {
        val machine = NetworkRoleMachine()
        machine.appActivated()
        machine.updateBattery(percent = 10, isCharging = false)

        // Todavía por debajo del umbral de batería baja (15%), pero
        // cargando — debe recuperar igual, sin esperar a superar 25%.
        machine.updateBattery(percent = 5, isCharging = true)

        assertEquals(NetworkRole.SOLO_RETRANSMITE, machine.state)
    }

    @Test
    fun batteryUpdateBelowRecoveryThresholdStaysInBajoConsumo() {
        val machine = NetworkRoleMachine()
        machine.appActivated()
        machine.updateBattery(percent = 10, isCharging = false)

        machine.updateBattery(percent = 20, isCharging = false) // ni <15 ni >25, ni cargando

        assertEquals(NetworkRole.BAJO_CONSUMO, machine.state)
    }

    @Test
    fun batteryUpdateIgnoredWhenNormalAndNotLow() {
        val machine = NetworkRoleMachine()
        machine.appActivated()

        machine.updateBattery(percent = 80, isCharging = false)

        assertEquals(NetworkRole.SOLO_RETRANSMITE, machine.state)
    }

    @Test
    fun batteryUpdateWhileAlreadyLowDoesNotRefireTransition() {
        val machine = NetworkRoleMachine()
        machine.appActivated()
        machine.updateBattery(percent = 10, isCharging = false)
        val observed = mutableListOf<NetworkRole>()
        machine.onTransition = { observed.add(it) }

        machine.updateBattery(percent = 8, isCharging = false) // sigue bajo, ya está en bajoConsumo

        assertEquals(NetworkRole.BAJO_CONSUMO, machine.state)
        assertEquals(emptyList(), observed)
    }

    @Test
    fun onTransitionFiresForEachTransition() {
        val machine = NetworkRoleMachine()
        val observed = mutableListOf<NetworkRole>()
        machine.onTransition = { observed.add(it) }

        machine.appActivated()
        machine.connectivityDetected()
        machine.nothingPendingToSync()
        machine.updateBattery(percent = 10, isCharging = false)
        machine.updateBattery(percent = 30, isCharging = false)

        assertEquals(
            listOf(
                NetworkRole.SOLO_RETRANSMITE,
                NetworkRole.GATEWAY_ACTIVO,
                NetworkRole.SINCRONIZADO_IDLE,
                NetworkRole.BAJO_CONSUMO,
                NetworkRole.SOLO_RETRANSMITE
            ),
            observed
        )
    }
}
