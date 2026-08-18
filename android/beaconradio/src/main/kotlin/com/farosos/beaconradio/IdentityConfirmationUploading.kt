package com.farosos.beaconradio

/**
 * Abstrae la subida del campo `identidad_verificada_caso_a` a
 * `participants/{device_id_hash}` (Firebase Auth + Firestore en la
 * implementación real, `FirebaseIdentityConfirmationUploader` en la capa de
 * app) para que `IdentityConfirmationUploader` sea testeable mockeando este
 * primitivo nativo en vez de sustituir la clase entera.
 */
interface IdentityConfirmationUploading {
    fun upload(deviceIdHash: ByteArray, onResult: (Result<Unit>) -> Unit)
}
