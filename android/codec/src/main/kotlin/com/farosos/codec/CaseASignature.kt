package com.farosos.codec

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Autocertificado de Caso A (`spec/packet-format.md`, sección
 * `FRAGMENTO_FIRMA`, decisión 18): el dispositivo firma su propia clave
 * pública Ed25519 — prueba posesión de la identidad, no autentica el
 * contenido de ningún beacon individual (límite aceptado, ver spec).
 * Vectores de prueba en `spec/test-vectors.json`, clave
 * `fragmento_firma.identity`. Ed25519 vía BouncyCastle — mismo motivo que en
 * `:app/DeviceIdentity` (#41): `java.security` no lo soporta de forma
 * confiable a minSdk 26.
 */
object CaseASignature {
    /** Tamaños fijos de Ed25519 — fuente única para quien necesite partir o validar un payload `pubkey || firma` (`SignatureFragmenter`). */
    const val PUBLIC_KEY_LENGTH: Int = 32
    const val SIGNATURE_LENGTH: Int = 64

    /** `firma = Ed25519_Sign(privkey, pubkey)`. */
    fun sign(privateKey: Ed25519PrivateKeyParameters): ByteArray {
        val publicKey = privateKey.generatePublicKey().encoded
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(publicKey, 0, publicKey.size)
        return signer.generateSignature()
    }

    /**
     * Verifica que [signature] sea un autocertificado válido de [publicKey]
     * (es decir, [signature] firma [publicKey] bajo esa misma clave).
     */
    fun verify(publicKey: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != PUBLIC_KEY_LENGTH) return false
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(publicKey, 0, publicKey.size)
        return runCatching { verifier.verifySignature(signature) }.getOrDefault(false)
    }
}
