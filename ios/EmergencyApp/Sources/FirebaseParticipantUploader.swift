import FirebaseAuth
import FirebaseFirestore
import Foundation
import ParticipantRegistration

/// Envoltorio de Firebase Auth (sesión anónima, una por instalación,
/// ADR-0003) + Firestore para subir `participants/{device_id_hash}`. Sin
/// protocolo propio más allá de `ParticipantUploading` (que sí vive en el
/// paquete testeado) — mismo molde que `BleAdvertiser`/`ConnectivityMonitor`:
/// la clase concreta real vive sin tests en la capa de app.
final class FirebaseParticipantUploader: ParticipantUploading {
    func upload(deviceIdHash: Data, profile: ParticipantProfile, completion: @escaping (Result<Void, Error>) -> Void) {
        ensureSignedIn { result in
            switch result {
            case .failure(let error):
                completion(.failure(error))
            case .success:
                let hashHex = ParticipantIds.deviceIdHashHex(deviceIdHash)
                var data: [String: Any] = [
                    "device_id_hash": hashHex,
                    "name": profile.name
                ]
                if let contact = profile.contact {
                    data["contacto"] = contact
                }
                Firestore.firestore().collection("participants").document(hashHex).setData(data) { error in
                    if let error {
                        completion(.failure(error))
                    } else {
                        completion(.success(()))
                    }
                }
            }
        }
    }

    private func ensureSignedIn(completion: @escaping (Result<Void, Error>) -> Void) {
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
