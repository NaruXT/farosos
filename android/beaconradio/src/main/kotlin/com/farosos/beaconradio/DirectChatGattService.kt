package com.farosos.beaconradio

import java.util.UUID

/**
 * Identificadores del servicio GATT del chat directo víctima↔rescatista
 * (#61/#63) — distinto de [BeaconGattService] (que es de solo lectura, para
 * el propio beacon). La víctima expone este servicio como servidor GATT
 * (rol periférico) únicamente mientras su propio estado es
 * `AYUDA_SOLICITADA`/`SILENCIO_TIMEOUT` (#61, decisión de batería).
 *
 * **Mismos valores y roles exactos deben usarse en iOS** (mismo principio
 * que `BeaconGattService`). Elegidos como continuación del mismo bloque de
 * UUIDs que `BeaconGattService` (`...9A10`/`...9A11`), incrementando el
 * último byte.
 *
 * Reescrito durante la verificación de campo real de #64 (2026-08-19): el
 * diseño original de Android trataba la clave efímera como una sola
 * característica de lectura+escritura combinada, y separaba mensajes en dos
 * UUIDs (escritura/notificación) - un modelo internamente consistente para
 * Android↔Android, pero incompatible con el de iOS (#62), que ya existía y
 * separa la clave del host (solo lectura) de la del rescatista (solo
 * escritura), y unifica los mensajes en una sola característica de
 * escritura+notificación. Se adoptó el modelo de iOS como canónico - separa
 * mejor las dos claves (que son datos distintos, aunque viajen por el mismo
 * intercambio) y ya estaba probado en campo contra un servidor GATT real.
 */
object DirectChatGattService {
    val SERVICE_UUID: UUID = UUID.fromString("6F415A2E-FA4C-4A2A-9A1B-2E9E6B5B9A20")

    /** Solo lectura: la clave pública X25519 efímera de la víctima (host) para esta conexión. */
    val HOST_PUBLIC_KEY_CHARACTERISTIC_UUID: UUID = UUID.fromString("6F415A2E-FA4C-4A2A-9A1B-2E9E6B5B9A21")

    /** Solo escritura: la clave pública X25519 efímera del rescatista (guest) para esta conexión. */
    val GUEST_PUBLIC_KEY_CHARACTERISTIC_UUID: UUID = UUID.fromString("6F415A2E-FA4C-4A2A-9A1B-2E9E6B5B9A22")

    /** Escritura (rescatista→víctima) + notificación (víctima→rescatista): mensajes cifrados en ambos sentidos, una sola característica. */
    val MESSAGE_CHARACTERISTIC_UUID: UUID = UUID.fromString("6F415A2E-FA4C-4A2A-9A1B-2E9E6B5B9A23")

    /** Descriptor estándar de configuración de cliente (CCCD) — necesario para habilitar notificaciones en la característica de arriba. */
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
