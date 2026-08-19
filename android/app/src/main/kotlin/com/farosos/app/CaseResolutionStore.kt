package com.farosos.app

import android.content.Context
import com.farosos.caseresolution.AttendingMark
import com.farosos.caseresolution.ResolutionMark

/**
 * Persiste las marcas "resuelto"/"atendiendo" pendientes de subir (#55) en
 * `EncryptedSharedPreferences`, mismo patrón que `ParticipantStore` — vive
 * en la capa de app, no en `:caseresolution`, porque requiere un `Context`
 * real. A diferencia de `ParticipantStore` (un solo perfil), acá puede
 * haber varias marcas pendientes a la vez, guardadas como un `StringSet`
 * de entradas codificadas.
 */
object CaseResolutionStore {
    private const val PREFS_FILE_NAME = "com.farosos.app.case_resolution"
    private const val KEY_PENDING_RESOLUTIONS = "pending_resolutions"
    private const val KEY_PENDING_ATTENDING = "pending_attending"
    private const val FIELD_SEPARATOR = "|"

    fun pendingResolutions(context: Context): List<ResolutionMark> =
        prefs(context).getStringSet(KEY_PENDING_RESOLUTIONS, emptySet()).orEmpty()
            .mapNotNull(::decodeResolution)

    fun pendingAttending(context: Context): List<AttendingMark> =
        prefs(context).getStringSet(KEY_PENDING_ATTENDING, emptySet()).orEmpty()
            .mapNotNull(::decodeAttending)

    fun addPendingResolution(mark: ResolutionMark, context: Context) {
        editStringSet(context, KEY_PENDING_RESOLUTIONS) { it + encodeResolution(mark) }
    }

    fun removePendingResolution(mark: ResolutionMark, context: Context) {
        editStringSet(context, KEY_PENDING_RESOLUTIONS) { it - encodeResolution(mark) }
    }

    fun addPendingAttending(mark: AttendingMark, context: Context) {
        editStringSet(context, KEY_PENDING_ATTENDING) { it + encodeAttending(mark) }
    }

    fun removePendingAttending(mark: AttendingMark, context: Context) {
        editStringSet(context, KEY_PENDING_ATTENDING) { it - encodeAttending(mark) }
    }

    private fun editStringSet(context: Context, key: String, transform: (Set<String>) -> Set<String>) {
        val prefs = prefs(context)
        val current = prefs.getStringSet(key, emptySet()).orEmpty()
        prefs.edit().putStringSet(key, transform(current)).apply()
    }

    private fun encodeResolution(mark: ResolutionMark): String = listOf(
        CaseResolutionHex.toHex(mark.victimDeviceIdHash),
        mark.victimSequence,
        mark.resolverLatitudeE7,
        mark.resolverLongitudeE7,
        mark.markedAtEpochSeconds
    ).joinToString(FIELD_SEPARATOR)

    private fun decodeResolution(raw: String): ResolutionMark? = runCatching {
        val parts = raw.split(FIELD_SEPARATOR)
        ResolutionMark(
            victimDeviceIdHash = CaseResolutionHex.fromHex(parts[0]),
            victimSequence = parts[1].toInt(),
            resolverLatitudeE7 = parts[2].toInt(),
            resolverLongitudeE7 = parts[3].toInt(),
            markedAtEpochSeconds = parts[4].toLong()
        )
    }.getOrNull()

    private fun encodeAttending(mark: AttendingMark): String = listOf(
        CaseResolutionHex.toHex(mark.victimDeviceIdHash),
        mark.victimSequence,
        mark.markedAtEpochSeconds
    ).joinToString(FIELD_SEPARATOR)

    private fun decodeAttending(raw: String): AttendingMark? = runCatching {
        val parts = raw.split(FIELD_SEPARATOR)
        AttendingMark(
            victimDeviceIdHash = CaseResolutionHex.fromHex(parts[0]),
            victimSequence = parts[1].toInt(),
            markedAtEpochSeconds = parts[2].toLong()
        )
    }.getOrNull()

    private fun prefs(context: Context) = EncryptedPrefsStore.open(PREFS_FILE_NAME, context)
}

private object CaseResolutionHex {
    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    fun fromHex(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
