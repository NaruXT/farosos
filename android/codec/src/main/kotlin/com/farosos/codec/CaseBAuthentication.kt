package com.farosos.codec

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ECDH + MAC de Caso B (`spec/packet-format.md`, sección "Layout Caso B" y
 * decisión 15). `K_shared` se deriva localmente sin handshake, contra la
 * clave pública X25519 fija del backend (#39) usando la clave privada
 * Ed25519 propia (#41) convertida a X25519 vía el mapa birracional estándar
 * — ninguna librería del proyecto expone esta conversión directamente, así
 * que se implementa aquí: `X25519_priv = clamp(SHA-512(seed_Ed25519)[:32])`,
 * verificado byte a byte contra el vector de ECDH de `spec/test-vectors.json`.
 * X25519 vía BouncyCastle — mismo motivo que Ed25519 en #41 (`java.security`
 * no lo soporta de forma confiable a minSdk 26).
 */
object CaseBAuthentication {
    /**
     * Clave pública X25519 real del backend (#48) — constante no-secreta,
     * embebida en el binario igual que `Company ID`/Service UUID. Generada
     * con entropía real (`backend/secrets/ecdh-backend-real-keypair.json`,
     * fuera de git), distinta de la clave determinística de prueba que usan
     * los vectores de `spec/test-vectors.json`
     * (`ecdh.backend_public_key_x25519_hex`) — esa sigue existiendo solo
     * para verificar la implementación del ECDH byte a byte, nunca se usa
     * en producción. La privada real vive en Firebase Secret Manager.
     */
    val BACKEND_PUBLIC_KEY_X25519: ByteArray =
        hexToBytes("4a5915cf192399053c2866bb76a781e92d4ca2e0c787067e2ce42fe44550ed67")

    /** `K_shared = X25519(clamp(SHA-512(privkey_Ed25519)[:32]), pubkey_X25519_backend)`. */
    fun deriveKShared(
        devicePrivateKeyEd25519Seed: ByteArray,
        backendPublicKeyX25519: ByteArray = BACKEND_PUBLIC_KEY_X25519
    ): ByteArray {
        val expanded = MessageDigest.getInstance("SHA-512").digest(devicePrivateKeyEd25519Seed)
        val scalar = expanded.copyOfRange(0, 32)
        scalar[0] = (scalar[0].toInt() and 0xF8).toByte()
        scalar[31] = ((scalar[31].toInt() and 0x7F) or 0x40).toByte()

        val devicePrivateKey = X25519PrivateKeyParameters(scalar, 0)
        val backendPublicKey = X25519PublicKeyParameters(backendPublicKeyX25519, 0)
        val agreement = X25519Agreement()
        agreement.init(devicePrivateKey)
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(backendPublicKey, sharedSecret, 0)
        return sharedSecret
    }

    /**
     * `contenido = DeviceIdHash(6) || TipoEstado(1) || Latitud(4) || Longitud(4) || Timestamp(4) || TTL(1) || Secuencia(1)` — 21 bytes.
     * No incluye `Magic`, `Versión` ni el propio `MAC`.
     */
    fun authenticatedContent(packet: CaseBBeaconPacket): ByteArray {
        val content = ByteArray(21)
        packet.deviceIdHash.copyInto(content, 0)
        content[6] = CaseBBeaconPacketCodec.tipoEstado(packet.messageType, packet.status)
        CaseBBeaconPacketCodec.putLeInt32(content, 7, packet.latitudeE7)
        CaseBBeaconPacketCodec.putLeInt32(content, 11, packet.longitudeE7)
        CaseBBeaconPacketCodec.putLeInt32(content, 15, packet.timestamp.toInt())
        content[19] = packet.ttl.toByte()
        content[20] = packet.sequence.toByte()
        return content
    }

    /** `MAC = HMAC-SHA256(K_shared, contenido)[:4]`. */
    fun computeMac(kShared: ByteArray, content: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(kShared, "HmacSHA256"))
        return mac.doFinal(content).copyOfRange(0, 4)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
