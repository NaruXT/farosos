package com.farosos.beaconradio

import java.util.UUID

/**
 * Identificadores del servicio GATT del chat directo víctima↔rescatista
 * (#61/#63) — distinto de [BeaconGattService] (que es de solo lectura, para
 * el propio beacon). La víctima expone este servicio como servidor GATT
 * (rol periférico) únicamente mientras su propio estado es
 * `AYUDA_SOLICITADA`/`SILENCIO_TIMEOUT` (#61, decisión de batería).
 *
 * **Mismos valores exactos deben usarse en iOS** (mismo principio que
 * `BeaconGattService`) — coordinar con la ticket #62 antes de que #64
 * (verificación de campo iOS↔Android) pueda pasar. Elegidos como
 * continuación del mismo bloque de UUIDs que `BeaconGattService`
 * (`...9A10`/`...9A11`), incrementando el último byte.
 */
object DirectChatGattService {
    val SERVICE_UUID: UUID = UUID.fromString("6F415A2E-FA4C-4A2A-9A1B-2E9E6B5B9A20")

    /** Escritura del central (rescatista): su clave pública X25519 efímera (32 bytes crudos). */
    val EPHEMERAL_PUBLIC_KEY_CHARACTERISTIC_UUID: UUID = UUID.fromString("6F415A2E-FA4C-4A2A-9A1B-2E9E6B5B9A21")

    /** Escritura del central hacia el periférico: un mensaje nuevo, cifrado (`IV || ciphertext+tag`). */
    val MESSAGE_WRITE_CHARACTERISTIC_UUID: UUID = UUID.fromString("6F415A2E-FA4C-4A2A-9A1B-2E9E6B5B9A22")

    /** Notificación del periférico hacia el central: historial + mensajes nuevos, cifrados. */
    val MESSAGE_NOTIFY_CHARACTERISTIC_UUID: UUID = UUID.fromString("6F415A2E-FA4C-4A2A-9A1B-2E9E6B5B9A23")

    /** Descriptor estándar de configuración de cliente (CCCD) — necesario para habilitar notificaciones en la característica de arriba. */
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
