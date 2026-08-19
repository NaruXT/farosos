package com.farosos.caseresolution

/**
 * Desacopla la subida de marcas "resuelto"/"atendiendo" (#55) de
 * `GATEWAY_ACTIVO` — igual que `ParticipantUploadCoordinator` (ADR-0003),
 * se dispara con cualquier señal de conectividad real del propio teléfono
 * del resolutor, sin depender del rol de red ni de la malla BLE (la
 * resolución nunca viaja por la malla, ver #55). A diferencia de
 * `ParticipantUploadCoordinator` (un solo perfil, una sola vez), acá puede
 * haber varias marcas pendientes a la vez — un participante puede marcar
 * más de un caso antes de recuperar señal.
 *
 * Cada `mark*` intenta subir de inmediato (por si el teléfono ya está
 * conectado en ese momento — `ConnectivityMonitor` solo dispara en
 * *cambios* de conectividad, no en cada marca nueva) y además queda
 * pendiente para el siguiente `connectivityDetected()` si ese intento
 * falla o no había señal.
 */
class CaseResolutionUploadCoordinator(
    private val resolverDeviceIdHash: ByteArray,
    private val uploader: CaseResolutionUploading,
    pendingResolutions: List<ResolutionMark> = emptyList(),
    pendingAttending: List<AttendingMark> = emptyList()
) {
    var onResolutionUploaded: ((ResolutionMark) -> Unit)? = null
    var onAttendingUploaded: ((AttendingMark) -> Unit)? = null

    private val pendingResolutions = pendingResolutions.toMutableList()
    private val pendingAttending = pendingAttending.toMutableList()
    private val uploadingResolutions = mutableSetOf<ResolutionMark>()
    private val uploadingAttending = mutableSetOf<AttendingMark>()

    fun markResolved(mark: ResolutionMark) {
        pendingResolutions.add(mark)
        uploadResolution(mark)
    }

    fun markAttending(mark: AttendingMark) {
        pendingAttending.add(mark)
        uploadAttending(mark)
    }

    /** Reintenta todo lo que sigue pendiente y no está ya en vuelo. */
    fun connectivityDetected() {
        pendingResolutions.filter { it !in uploadingResolutions }.forEach(::uploadResolution)
        pendingAttending.filter { it !in uploadingAttending }.forEach(::uploadAttending)
    }

    private fun uploadResolution(mark: ResolutionMark) {
        if (!uploadingResolutions.add(mark)) return
        uploader.uploadResolved(mark, resolverDeviceIdHash) { result ->
            uploadingResolutions.remove(mark)
            result.onSuccess {
                pendingResolutions.remove(mark)
                onResolutionUploaded?.invoke(mark)
            }
            // en caso de fallo, sigue pendiente y se reintenta en la
            // próxima señal de conectividad
        }
    }

    private fun uploadAttending(mark: AttendingMark) {
        if (!uploadingAttending.add(mark)) return
        uploader.uploadAttending(mark, resolverDeviceIdHash) { result ->
            uploadingAttending.remove(mark)
            result.onSuccess {
                pendingAttending.remove(mark)
                onAttendingUploaded?.invoke(mark)
            }
        }
    }
}
