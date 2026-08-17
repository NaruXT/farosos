package com.farosos.participantregistration

/**
 * Abstrae la subida a `participants/{device_id_hash}` (Firebase Auth +
 * Firestore en la implementación real, `FirebaseParticipantUploader` en la
 * capa de app) para que `ParticipantUploadCoordinator` sea testeable sin
 * tocar red.
 */
interface ParticipantUploading {
    fun upload(deviceIdHash: ByteArray, profile: ParticipantProfile, onResult: (Result<Unit>) -> Unit)
}
