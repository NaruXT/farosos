package com.farosos.directchat

import java.security.SecureRandom
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/**
 * ECDH efímero por conexión (#61) — a diferencia de `CaseBAuthentication`
 * (que deriva `K_shared` a partir de la clave Ed25519 permanente del
 * dispositivo, convertida a X25519 vía el mapa birracional), acá cada
 * conexión de chat genera un par de claves X25519 nuevo desde cero. Se
 * descartó reusar la identidad permanente porque haría falta convertir la
 * clave pública Ed25519 *del otro dispositivo* a X25519 — una conversión
 * que ninguna librería del proyecto expone y que no existe todavía del lado
 * del cliente (solo existe en el backend, en JavaScript, para verificar
 * MACs de Caso B). Un par efímero nativo evita ese problema por completo:
 * ni BouncyCastle ni el resto de la librería necesitan convertir nada.
 *
 * Protege el contenido del chat contra un tercero no conectado escuchando
 * el aire — no ata la clave a la identidad permanente del dispositivo, algo
 * aceptado explícitamente durante la sesión de `/grilling` de #61 (historia
 * 11): el modelo de amenaza de este piloto (ADR-0003) no exige más que eso.
 */
object EphemeralKeyExchange {
    data class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun generateKeyPair(secureRandom: SecureRandom = SecureRandom()): KeyPair {
        val generator = X25519KeyPairGenerator()
        generator.init(X25519KeyGenerationParameters(secureRandom))
        val keyPair = generator.generateKeyPair()
        val privateKey = (keyPair.private as X25519PrivateKeyParameters).encoded
        val publicKey = (keyPair.public as X25519PublicKeyParameters).encoded
        return KeyPair(privateKey, publicKey)
    }

    /** Secreto compartido crudo (32 bytes) — sin derivar todavía a clave de sesión, ver [ChatCrypto.deriveSessionKey]. */
    fun agree(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey, 0))
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicKey, 0), sharedSecret, 0)
        return sharedSecret
    }
}
