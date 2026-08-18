import BeaconRadio
import FirebaseFirestore
import Foundation
import ParticipantRegistration

/// Envoltorio de Firebase Auth (reutiliza la sesión anónima ya establecida
/// en el registro de identidad, #29) + Firestore para subir el campo
/// `identidad_verificada_caso_a` de `participants/{device_id_hash}`
/// (ticket #52). Sin protocolo propio más allá de
/// `IdentityConfirmationUploading` (que sí vive en el paquete testeado) —
/// mismo molde que `FirebaseMeshStateUploader`/`FirebaseParticipantUploader`:
/// la clase concreta real vive sin tests en la capa de app.
final class FirebaseIdentityConfirmationUploader: IdentityConfirmationUploading {
    func upload(deviceIdHash: Data, completion: @escaping (Result<Void, Error>) -> Void) {
        FirebaseAuthSession.ensureSignedIn { result in
            switch result {
            case .failure(let error):
                completion(.failure(error))
            case .success:
                let hashHex = ParticipantIds.deviceIdHashHex(deviceIdHash)
                let data: [String: Any] = [
                    "device_id_hash": hashHex,
                    "identidad_verificada_caso_a": true
                ]
                // `merge: true` — a diferencia de `FirebaseParticipantUploader`
                // (que sube el perfil completo del propio dueño) — es
                // obligatorio acá: este documento puede pertenecer a OTRO
                // dispositivo que ya tiene `name`/`contacto` subidos por su
                // propio registro (o los suba después); un `set` sin merge
                // los borraría (AC de #52).
                Firestore.firestore().collection("participants").document(hashHex).setData(data, merge: true) { error in
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
