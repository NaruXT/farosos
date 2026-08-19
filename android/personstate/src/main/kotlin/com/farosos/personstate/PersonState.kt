package com.farosos.personstate

import com.farosos.codec.BeaconPacket

/** Máquina de estados A (persona), definida en `spec/packet-format.md`. */
enum class PersonState {
    DORMIDO,
    ACTIVO_SIN_CONFIRMAR,
    ESPERANDO_CONFIRMACION,
    CONFIRMADO_OK,
    AYUDA_SOLICITADA,
    SILENCIO_TIMEOUT;

    /**
     * El `Estado` de wire (`BeaconPacket.Status`) que corresponde a este
     * estado de persona — única fuente de esa correspondencia, para que
     * `PersonStateMachine.transition` no tenga que repetirla en cada call
     * site.
     */
    val beaconStatus: BeaconPacket.Status
        get() = when (this) {
            DORMIDO, ACTIVO_SIN_CONFIRMAR, ESPERANDO_CONFIRMACION -> BeaconPacket.Status.SIN_CONFIRMAR
            CONFIRMADO_OK -> BeaconPacket.Status.OK
            AYUDA_SOLICITADA -> BeaconPacket.Status.AYUDA
            SILENCIO_TIMEOUT -> BeaconPacket.Status.SILENCIO_TIMEOUT
        }

    /**
     * Quien está pidiendo ayuda no está en posición de atender/resolver
     * casos ajenos (#55) ni de exponer el servicio de chat directo como
     * víctima activa (#61 — el chat se activa/desactiva junto con este
     * mismo predicado). Única fuente de la lista de estados "pidiendo
     * ayuda", para no repetir la comparación en cada call site.
     */
    val isRequestingHelp: Boolean
        get() = this == AYUDA_SOLICITADA || this == SILENCIO_TIMEOUT
}
