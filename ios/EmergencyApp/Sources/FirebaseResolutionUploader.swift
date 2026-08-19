import BeaconRadio
import CaseResolution
import FirebaseFirestore
import Foundation

/// Envoltorio de Firebase Auth (reutiliza la sesión anónima, ADR-0003) +
/// Firestore para subir los campos de "resuelto"/"atendiendo" (#55) sobre
/// `mesh_states/{device_id_hash}_{sequence}` — mismo molde que
/// `FirebaseMeshStateUploader`, pero `merge: true` sobre un documento que
/// puede o no existir todavía (`isResolutionWrite()`, #56, permite la
/// creación parcial). Reusa `MeshStateIds` de `BeaconRadio` para no repetir
/// la convención de IDs de ADR-0002.
final class FirebaseResolutionUploader: ResolutionUploading {
    func uploadResolved(_ mark: ResolvedMark, completion: @escaping (Result<Void, Error>) -> Void) {
        write(
            docId: MeshStateIds.docId(deviceIdHash: mark.victimDeviceIdHash, sequence: mark.victimSequence),
            data: [
                "device_id_hash": MeshStateIds.deviceIdHashHex(mark.victimDeviceIdHash),
                "sequence": Int(mark.victimSequence),
                "resuelto": true,
                "resuelto_por": MeshStateIds.deviceIdHashHex(mark.resolverDeviceIdHash),
                "resuelto_en": Int(mark.markedAt),
                "resolutor_latitud_e7": Int(mark.resolverLatitudeE7),
                "resolutor_longitud_e7": Int(mark.resolverLongitudeE7)
            ],
            completion: completion
        )
    }

    func uploadAttending(_ mark: AttendingMark, completion: @escaping (Result<Void, Error>) -> Void) {
        let entry: [String: Any] = [
            "device_id_hash": MeshStateIds.deviceIdHashHex(mark.resolverDeviceIdHash),
            "marcado_en": Int(mark.markedAt)
        ]
        write(
            docId: MeshStateIds.docId(deviceIdHash: mark.victimDeviceIdHash, sequence: mark.victimSequence),
            data: [
                "device_id_hash": MeshStateIds.deviceIdHashHex(mark.victimDeviceIdHash),
                "sequence": Int(mark.victimSequence),
                "atendido_por": FieldValue.arrayUnion([entry])
            ],
            completion: completion
        )
    }

    /// `merge: true` sobre un documento que puede o no existir todavía
    /// (`isResolutionWrite()`, #56, permite la creación parcial) — mismo
    /// paso final para ambas señales, solo cambian los campos.
    private func write(docId: String, data: [String: Any], completion: @escaping (Result<Void, Error>) -> Void) {
        FirebaseAuthSession.ensureSignedIn { result in
            switch result {
            case .failure(let error):
                completion(.failure(error))
            case .success:
                Firestore.firestore().collection("mesh_states").document(docId).setData(data, merge: true) { error in
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
