package com.farosos.beaconradio

import java.util.UUID

/**
 * Identificadores del servicio/característica GATT que usa el emisor de iOS
 * para publicar su `BeaconPacket` (ticket #11, revisión de la decisión 13 —
 * `spec/packet-format.md`): `CBPeripheralManager` en iOS no puede anunciar
 * Manufacturer Specific Data, así que solo señaliza su presencia con este
 * Service UUID; el paquete real de 26 bytes viaja como el valor de esta
 * característica, que un central debe conectarse a leer.
 *
 * Mismos valores exactos que `BeaconGattService.swift` en iOS — tienen que
 * coincidir byte a byte entre plataformas, no son negociables.
 */
object BeaconGattService {
    val SERVICE_UUID: UUID = UUID.fromString("6F415A2E-FA4C-4A2A-9A1B-2E9E6B5B9A10")
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("6F415A2E-FA4C-4A2A-9A1B-2E9E6B5B9A11")
}
