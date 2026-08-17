import FirebaseAuth
import Foundation

/// Sesión anónima compartida por los distintos uploaders de Firebase
/// (`FirebaseParticipantUploader` #29, `FirebaseMeshStateUploader` #31) — una
/// sola por instalación (ADR-0003), sin volver a firmarse si ya hay una
/// activa.
enum FirebaseAuthSession {
    static func ensureSignedIn(completion: @escaping (Result<Void, Error>) -> Void) {
        if Auth.auth().currentUser != nil {
            completion(.success(()))
            return
        }
        Auth.auth().signInAnonymously { _, error in
            if let error {
                completion(.failure(error))
            } else {
                completion(.success(()))
            }
        }
    }
}
