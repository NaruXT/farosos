package com.farosos.app

import android.content.Context
import android.util.Base64
import com.farosos.deviceidentity.DeviceIdentityHash
import com.farosos.deviceidentity.ProofOfWork
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
    private const val KEY_PROOF_OF_WORK_NONCE = "proofOfWorkNonce"

    fun deviceIdHash(context: Context): ByteArray =
        DeviceIdentityHash.fromPublicKey(publicKeyEd25519(context))

    /**
     * Clave pública Ed25519 cruda (32 bytes) — la sube el registro opt-in
     * (#47) para que el backend pueda derivar `K_shared` por ECDH en Caso B
     * (#38/#48). Nunca la clave privada, que no sale del dispositivo.
     */
    fun publicKeyEd25519(context: Context): ByteArray =
        privateKey(context).generatePublicKey().encoded

    /**
     * Mitigación Sybil de Caso A (#51) — calcula el sello de Prueba de
     * Trabajo sobre `deviceIdHash` una única vez y lo persiste, igual que la
     * identidad Ed25519. Si ya hay un sello guardado y sigue siendo válido
     * (mismo `deviceIdHash`, cumple `ProofOfWork.DIFFICULTY_BITS` actual) lo
     * reutiliza sin recalcular; si no, lo recalcula — cubre tanto la primera
     * vez como un cambio futuro de dificultad o de identidad
     * (reinstalación). Recibe `deviceIdHash` en vez de volver a derivarlo,
     * porque quien llama ya lo tiene.
     */
    fun proofOfWorkSeal(context: Context, deviceIdHash: ByteArray): ByteArray {
        val prefs = EncryptedPrefsStore.open(PREFS_FILE_NAME, context)
        prefs.getString(KEY_PROOF_OF_WORK_NONCE, null)?.let { stored ->
            val nonce = Base64.decode(stored, Base64.NO_WRAP)
            if (ProofOfWork.isValid(deviceIdHash, nonce)) {
                return nonce
            }
        }
        val nonce = ProofOfWork.solve(deviceIdHash)
        prefs.edit()
            .putString(KEY_PROOF_OF_WORK_NONCE, Base64.encodeToString(nonce, Base64.NO_WRAP))
            .apply()
        return nonce
    }

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
