package com.farosos.caseresolution

/**
 * Marca "atendiendo" (#55, "voy a socorrer") sobre el caso de otro
 * participante — sin ubicación ni verificación de proximidad, a diferencia
 * de [ResolutionMark]: es una señal de intención de menor consecuencia que
 * admite múltiples resolutores en paralelo sobre el mismo caso
 * (`atendido_por`, vía `arrayUnion` en el backend).
 */
data class AttendingMark(
    val victimDeviceIdHash: ByteArray,
    val victimSequence: Int,
    val markedAtEpochSeconds: Long
) {
    override fun equals(other: Any?): Boolean =
        other is AttendingMark &&
            victimDeviceIdHash.contentEquals(other.victimDeviceIdHash) &&
            victimSequence == other.victimSequence &&
            markedAtEpochSeconds == other.markedAtEpochSeconds

    override fun hashCode(): Int {
        var result = victimDeviceIdHash.contentHashCode()
        result = 31 * result + victimSequence
        result = 31 * result + markedAtEpochSeconds.hashCode()
        return result
    }
}
