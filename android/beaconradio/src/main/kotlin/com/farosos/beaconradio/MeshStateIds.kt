package com.farosos.beaconradio

/**
 * Misma convención de IDs que `backend/lib/ids.mjs` (`meshStateDocId`): el
 * ID de documento es `{device_id_hash}_{sequence}` (ADR-0002, dedup
 * multi-gateway) — así dos gateways subiendo el mismo (persona, secuencia)
 * escriben siempre el mismo documento.
 */
object MeshStateIds {
    fun deviceIdHashHex(deviceIdHash: ByteArray): String =
        deviceIdHash.joinToString(separator = "") { "%02x".format(it) }

    fun docId(deviceIdHash: ByteArray, sequence: Int): String =
        "${deviceIdHashHex(deviceIdHash)}_$sequence"
}
