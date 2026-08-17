import BeaconRadio
import FirebaseAuth
import FirebaseFirestore
import Foundation
import PacketCodec

/// Envoltorio de Firebase Auth (reutiliza la sesión anónima ya establecida
/// en el registro de identidad, #29) + Firestore para subir
/// `mesh_states/{device_id_hash}_{sequence}` (ADR-0002). Sin protocolo
/// propio más allá de `MeshStateUploading` (que sí vive en el paquete
/// testeado) — mismo molde que `FirebaseParticipantUploader`: la clase
/// concreta real vive sin tests en la capa de app.
final class FirebaseMeshStateUploader: MeshStateUploading {
    func upsert(_ state: MeshParticipantState, completion: @escaping (Result<Void, Error>) -> Void) {
        FirebaseAuthSession.ensureSignedIn { result in
            switch result {
            case .failure(let error):
                completion(.failure(error))
            case .success:
                let docId = MeshStateIds.docId(deviceIdHash: state.deviceIdHash, sequence: state.sequence)
                let data: [String: Any] = [
                    "device_id_hash": MeshStateIds.deviceIdHashHex(state.deviceIdHash),
                    "status": Self.statusString(state.status),
                    "latitude": Double(state.latitudeE7) / 1e7,
                    "longitude": Double(state.longitudeE7) / 1e7,
                    "beacon_timestamp": Int(state.timestamp),
                    "sequence": Int(state.sequence),
                    "uploaded_at": Int(Date().timeIntervalSince1970),
                    "confirmed_by_gateways": FieldValue.arrayUnion([Auth.auth().currentUser?.uid ?? ""])
                ]
                // `merge: true` es necesario para que `arrayUnion` acumule
                // sobre el documento existente (ADR-0002) en vez de
                // arriesgarse a que un `set` sin merge lo trate como vacío.
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

    private static func statusString(_ status: BeaconPacket.Status) -> String {
        switch status {
        case .sinConfirmar: return "SIN_CONFIRMAR"
        case .ok: return "OK"
        case .ayuda: return "AYUDA"
        case .silencioTimeout: return "SILENCIO_TIMEOUT"
        case .gatewayDisponible: return "GATEWAY_DISPONIBLE"
        }
    }
}
