package com.farosos.app

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.util.UUID

/**
 * Persiste el UUID de instalación en `EncryptedSharedPreferences` (decisión
 * de arquitectura 6, `spec/packet-format.md`) y deriva el `deviceIdHash` de
 * 6 bytes (`SHA-256(UUID)` truncado) que viaja en cada `BeaconPacket`
 * emitido por este nodo. Vive en la capa de app, no en `:beaconradio` —
 * requiere un `Context` real, igual que `RealScheduler`.
 */
object DeviceIdentity {
    private const val PREFS_FILE_NAME = "com.farosos.app.device_identity"
    private const val KEY_INSTALLATION_ID = "installationId"

    fun deviceIdHash(context: Context): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(installationUUIDString(context).toByteArray(Charsets.UTF_8))
        return digest.copyOfRange(0, 6)
    }

    private fun installationUUIDString(context: Context): String {
        val prefs = encryptedPrefs(context)
        prefs.getString(KEY_INSTALLATION_ID, null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALLATION_ID, generated).apply()
        return generated
    }

    // androidx.security-crypto 1.1.0 marca EncryptedSharedPreferences/MasterKey
    // como deprecados sin un reemplazo estable documentado todavía — se
    // mantienen porque siguen siendo funcionales y son la API explícita que
    // pide la decisión 6 del spec.
    private fun encryptedPrefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
