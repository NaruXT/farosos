package com.farosos.caseresolution

/**
 * Abstrae la subida de marcas "resuelto"/"atendiendo" sobre
 * `mesh_states/{device_id_hash}_{sequence}` (Firebase Auth + Firestore en
 * la implementación real, `FirebaseCaseResolutionUploader` en la capa de
 * app) para que [CaseResolutionUploadCoordinator] sea testeable sin tocar
 * red.
 */
interface CaseResolutionUploading {
    fun uploadResolved(mark: ResolutionMark, resolverDeviceIdHash: ByteArray, onResult: (Result<Unit>) -> Unit)
    fun uploadAttending(mark: AttendingMark, resolverDeviceIdHash: ByteArray, onResult: (Result<Unit>) -> Unit)
}
