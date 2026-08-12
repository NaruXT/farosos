package com.farosos.networkrole

/**
 * Máquina de estados B (rol de red del teléfono), definida en
 * `spec/packet-format.md` — independiente de la Máquina A (estado de la
 * persona). Fase 2 (ticket #14): foreground-only, sin BLE ni acceso real a
 * batería/conectividad; las señales llegan inyectadas desde afuera.
 */
enum class NetworkRole {
    APAGADO,
    SOLO_RETRANSMITE,
    GATEWAY_ACTIVO,
    SINCRONIZADO_IDLE,
    BAJO_CONSUMO
}
