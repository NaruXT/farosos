package com.farosos.beaconradio

import kotlin.random.Random

/**
 * Genera un nonce aleatorio por beacon (`spec/packet-format.md`, campo
 * Nonce) — inyectable para que `LocalBeaconFactory` sea testeable sin
 * depender de aleatoriedad real.
 */
interface NonceGenerating {
    fun nextNonce(): Int
}

class RandomNonceGenerator : NonceGenerating {
    override fun nextNonce(): Int = Random.nextInt(0, 0x10000)
}
