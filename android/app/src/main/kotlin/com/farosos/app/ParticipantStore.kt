package com.farosos.app

import android.content.Context
import com.farosos.participantregistration.ParticipantProfile

/**
 * Persiste el perfil de registro opt-in (ADR-0003) en
 * `EncryptedSharedPreferences`, mismo patrón que `DeviceIdentity`. Vive en
 * la capa de app, no en `:participantregistration` — requiere un `Context`
 * real.
 */
object ParticipantStore {
    private const val PREFS_FILE_NAME = "com.farosos.app.participant_identity"
    private const val KEY_NAME = "name"
    private const val KEY_CONTACT = "contact"
    private const val KEY_UPLOADED = "uploaded"

    fun hasRegisteredProfile(context: Context): Boolean = prefs(context).getString(KEY_NAME, null) != null

    /**
     * Guarda el perfil localmente sin marcarlo como subido — queda
     * pendiente hasta que `ParticipantUploadCoordinator` confirme la
     * subida vía `markUploaded()`.
     */
    fun save(profile: ParticipantProfile, context: Context) {
        prefs(context).edit().apply {
            putString(KEY_NAME, profile.name)
            profile.contact?.let { putString(KEY_CONTACT, it) }
        }.apply()
    }

    /**
     * El perfil guardado si todavía no se subió a `participants` — null si
     * no hay perfil registrado, o si ya se subió con éxito.
     */
    fun pendingProfile(context: Context): ParticipantProfile? {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_UPLOADED, false)) return null
        val name = prefs.getString(KEY_NAME, null) ?: return null
        return ParticipantProfile(name = name, contact = prefs.getString(KEY_CONTACT, null))
    }

    fun markUploaded(context: Context) {
        prefs(context).edit().putBoolean(KEY_UPLOADED, true).apply()
    }

    private fun prefs(context: Context) = EncryptedPrefsStore.open(PREFS_FILE_NAME, context)
}
