package com.farosos.app

import android.content.Context
import android.util.Base64
import com.farosos.deviceidentity.DeviceIdentityHash
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.security.SecureRandom

/**
 * Genera y persiste un keypair Ed25519 en `EncryptedSharedPreferences` al
 * instalar (decisión de arquitectura 17, `spec/packet-format.md` —
 * reemplaza el UUID de instalación anterior) y deriva el `deviceIdHash` de
 * 6 bytes (`DeviceIdentityHash.fromPublicKey`, testeado contra los vectores
 * de #39) que viaja en cada `BeaconPacket` emitido por este nodo. La clave
 * privada nunca sale del dispositivo. Ed25519 vía BouncyCastle porque no hay
 * soporte confiable en `java.security` a este minSdk (ni el provider de
 * plataforma ni Play Services lo agregan; AndroidKeyStore lo soporta recién
 * desde API 33, y solo con backing de hardware). Vive en la capa de app, no
 * en `:beaconradio` — requiere un `Context` real, igual que `RealScheduler`.
 */
object DeviceIdentity {
    private const val PREFS_FILE_NAME = "com.farosos.app.device_identity"
    private const val KEY_ED25519_PRIVATE = "ed25519PrivateKey"

    fun deviceIdHash(context: Context): ByteArray =
        DeviceIdentityHash.fromPublicKey(privateKey(context).generatePublicKey().encoded)

    private fun privateKey(context: Context): Ed25519PrivateKeyParameters {
        val prefs = EncryptedPrefsStore.open(PREFS_FILE_NAME, context)
        prefs.getString(KEY_ED25519_PRIVATE, null)?.let { stored ->
            runCatching { Ed25519PrivateKeyParameters(Base64.decode(stored, Base64.NO_WRAP), 0) }
                .getOrNull()
                ?.let { return it }
        }
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val generated = generator.generateKeyPair().private as Ed25519PrivateKeyParameters
        prefs.edit()
            .putString(KEY_ED25519_PRIVATE, Base64.encodeToString(generated.encoded, Base64.NO_WRAP))
            .apply()
        return generated
    }
}
