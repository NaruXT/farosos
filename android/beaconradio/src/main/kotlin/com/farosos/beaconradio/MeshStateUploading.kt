package com.farosos.beaconradio

/**
 * Abstrae la subida de un `MeshParticipantState` a
 * `mesh_states/{device_id_hash}_{sequence}` (Firebase Auth + Firestore en la
 * implementación real, `FirebaseMeshStateUploader` en la capa de app) para
 * que `GatewayUploader` sea testeable mockeando este primitivo nativo en vez
 * de sustituir la clase entera.
 */
interface MeshStateUploading {
    fun upsert(state: MeshParticipantState, onResult: (Result<Unit>) -> Unit)
}
