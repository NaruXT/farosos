package com.farosos.caseresolution

/**
 * Misma convención de IDs que `backend/lib/ids.mjs` (`meshStateDocId`) y
 * `MeshStateIds` de `:beaconradio` (ADR-0002, dedup multi-gateway) —
 * reimplementada acá en vez de depender de `:beaconradio` para que este
 * módulo se quede sin dependencias de proyecto, mismo criterio que ya
 * separa `MeshStateIds`/`ParticipantIds` entre sí.
 */
object CaseResolutionIds {
    fun deviceIdHashHex(deviceIdHash: ByteArray): String =
        deviceIdHash.joinToString(separator = "") { "%02x".format(it) }

    fun meshStateDocId(deviceIdHash: ByteArray, sequence: Int): String =
        "${deviceIdHashHex(deviceIdHash)}_$sequence"
}
