package com.farosos.app

import com.farosos.personstate.PersonState

/** Entrada del log en pantalla — mecanismo principal de verificación en campo. */
class LogEntry(val timestampMillis: Long, val kind: Kind) {
    sealed class Kind {
        class Transition(val state: PersonState, val sequence: Int) : Kind()
        class BeaconReceived(val deviceIdHash: ByteArray, val ttl: Int, val sequence: Int) : Kind()
        class DuplicateDiscarded(val deviceIdHash: ByteArray, val nonce: Int) : Kind()
        class Info(val message: String) : Kind()
    }
}
