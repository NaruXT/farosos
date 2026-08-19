import FirebaseFirestore
import Foundation
import ParticipantRegistration

/// Envoltorio de Firebase Auth (sesión anónima, una por instalación,
/// ADR-0003) + Firestore para subir `participants/{device_id_hash}`. Sin
/// protocolo propio más allá de `ParticipantUploading` (que sí vive en el
/// paquete testeado) — mismo molde que `BleAdvertiser`/`ConnectivityMonitor`:
/// la clase concreta real vive sin tests en la capa de app.
final class FirebaseParticipantUploader: ParticipantUploading {
    func upload(deviceIdHash: Data, publicKeyEd25519: Data, profile: ParticipantProfile, completion: @escaping (Result<Void, Error>) -> Void) {
        FirebaseAuthSession.ensureSignedIn { result in
            switch result {
            case .failure(let error):
                completion(.failure(error))
            case .success:
                let hashHex = ParticipantIds.deviceIdHashHex(deviceIdHash)
                var data: [String: Any] = [
                    "device_id_hash": hashHex,
                    "public_key_ed25519": publicKeyEd25519.map { String(format: "%02x", $0) }.joined(),
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
}
