package com.farosos.beaconradio

/**
 * Company ID de Manufacturer Specific Data del protocolo de Farosos
 * (`spec/packet-format.md`) — compartido entre `BleAdvertiser` y
 * `BleScanner` en la capa de app para que emisor y receptor no puedan
 * desincronizarse por editar una constante duplicada en un solo lado.
 */
const val BEACON_COMPANY_ID = 0xFFFF
