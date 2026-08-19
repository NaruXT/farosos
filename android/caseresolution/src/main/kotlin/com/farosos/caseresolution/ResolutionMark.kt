package com.farosos.caseresolution

/**
 * Marca "resuelto" (#55) sobre el caso de otro participante — captura la
 * secuencia de la víctima conocida al momento de marcar (no la más
 * reciente que exista al subir, para no marcar como resuelto un estado que
 * el resolutor nunca llegó a ver) y la ubicación propia del resolutor, para
 * la verificación de proximidad asíncrona del backend (#59).
 */
data class ResolutionMark(
    val victimDeviceIdHash: ByteArray,
    val victimSequence: Int,
    val resolverLatitudeE7: Int,
    val resolverLongitudeE7: Int,
    val markedAtEpochSeconds: Long
) {
    override fun equals(other: Any?): Boolean =
        other is ResolutionMark &&
            victimDeviceIdHash.contentEquals(other.victimDeviceIdHash) &&
            victimSequence == other.victimSequence &&
            resolverLatitudeE7 == other.resolverLatitudeE7 &&
            resolverLongitudeE7 == other.resolverLongitudeE7 &&
            markedAtEpochSeconds == other.markedAtEpochSeconds

    override fun hashCode(): Int {
        var result = victimDeviceIdHash.contentHashCode()
        result = 31 * result + victimSequence
        result = 31 * result + resolverLatitudeE7
        result = 31 * result + resolverLongitudeE7
        result = 31 * result + markedAtEpochSeconds.hashCode()
        return result
    }
}
