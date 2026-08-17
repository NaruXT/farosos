package com.farosos.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Envoltorio genérico de creación de `EncryptedSharedPreferences` bajo un
 * nombre de archivo dado — `DeviceIdentity` y `ParticipantStore` lo usan en
 * vez de repetir el boilerplate de `MasterKey`/esquemas de cifrado.
 */
object EncryptedPrefsStore {
    // androidx.security-crypto 1.1.0 marca EncryptedSharedPreferences/MasterKey
    // como deprecados sin un reemplazo estable documentado todavía — se
    // mantienen porque siguen siendo funcionales y son la API explícita que
    // pide la decisión 6 del spec.
    fun open(fileName: String, context: Context): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        fileName,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
