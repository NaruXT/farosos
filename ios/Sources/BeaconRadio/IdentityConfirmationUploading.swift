import Foundation

/// Abstrae la subida a `participants/{device_id_hash}` del campo
/// `identidad_verificada_caso_a` (Firebase Auth + Firestore en la
/// implementación real, `FirebaseIdentityConfirmationUploader` en la capa
/// de app) para que `IdentityConfirmationUploader` sea testeable sin tocar
/// red — mismo molde que `MeshStateUploading`/`ParticipantUploading`.
public protocol IdentityConfirmationUploading {
    func upload(deviceIdHash: Data, completion: @escaping (Result<Void, Error>) -> Void)
}
