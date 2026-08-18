package com.farosos.deviceidentity

import java.security.MessageDigest

/**
 * `device_id_hash = SHA-256(clave pública Ed25519)[:6 bytes]` — ver
 * `spec/packet-format.md` decisión 17. Puro y testeable: quien genera y
 * persiste el keypair real (`DeviceIdentity`, capa de app) delega aquí el
 * cómputo del hash.
 */
object DeviceIdentityHash {
    fun fromPublicKey(publicKey: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(publicKey).copyOfRange(0, 6)
}
