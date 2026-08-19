import Foundation

/// Abstrae la subida a `mesh_states/{device_id_hash}_{sequence}` (Firebase
/// Auth + Firestore `merge: true` en la implementación real,
/// `FirebaseResolutionUploader` en la capa de app) para que
/// `ResolutionUploadCoordinator` sea testeable sin tocar red — mismo rol que
/// `ParticipantUploading`.
public protocol ResolutionUploading {
    func uploadResolved(_ mark: ResolvedMark, completion: @escaping (Result<Void, Error>) -> Void)
    func uploadAttending(_ mark: AttendingMark, completion: @escaping (Result<Void, Error>) -> Void)
}
