import Foundation

/// Abstrae la subida de un `MeshParticipantState` a `mesh_states/{device_id_hash}_{sequence}`
/// (Firebase Auth + Firestore en la implementación real, `FirebaseMeshStateUploader`
/// en la capa de app) para que `GatewayUploader` sea testeable mockeando este
/// primitivo nativo en vez de sustituir la clase entera.
public protocol MeshStateUploading {
    func upsert(_ state: MeshParticipantState, completion: @escaping (Result<Void, Error>) -> Void)
}
