import Foundation

/// Abstrae la subida a `participants/{device_id_hash}` (Firebase Auth +
/// Firestore en la implementación real, `FirebaseParticipantUploader` en la
/// capa de app) para que `ParticipantUploadCoordinator` sea testeable sin
/// tocar red.
public protocol ParticipantUploading {
    func upload(deviceIdHash: Data, profile: ParticipantProfile, completion: @escaping (Result<Void, Error>) -> Void)
}
