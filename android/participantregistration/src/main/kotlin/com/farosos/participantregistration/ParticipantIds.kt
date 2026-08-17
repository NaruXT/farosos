package com.farosos.participantregistration

/**
 * Misma convención de IDs que `backend/lib/ids.mjs` (`participantDocId`):
 * el ID de documento es el propio `device_id_hash` en hex — así las reglas
 * de Firestore (`matchesParticipantId`) pueden comparar el docId contra el
 * campo sin ambigüedad de formato.
 */
object ParticipantIds {
    fun deviceIdHashHex(deviceIdHash: ByteArray): String =
        deviceIdHash.joinToString(separator = "") { "%02x".format(it) }

    fun participantDocId(deviceIdHash: ByteArray): String = deviceIdHashHex(deviceIdHash)
}
