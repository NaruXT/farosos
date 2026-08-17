package com.farosos.app

import com.farosos.beaconradio.MeshParticipantState
import com.farosos.beaconradio.MeshStateIds
import com.farosos.beaconradio.MeshStateUploading
import com.farosos.codec.BeaconPacket
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore

/**
 * Envoltorio de Firebase Auth (reutiliza la sesión anónima ya establecida
 * en el registro de identidad, #30) + Firestore para subir
 * `mesh_states/{device_id_hash}_{sequence}` (ADR-0002). Sin interfaz propia
 * más allá de `MeshStateUploading` (que sí vive en el módulo testeado) —
 * mismo molde que `FirebaseParticipantUploader`: la clase concreta real
 * vive sin tests en la capa de app.
 */
class FirebaseMeshStateUploader : MeshStateUploading {
    override fun upsert(state: MeshParticipantState, onResult: (Result<Unit>) -> Unit) {
        FirebaseAuthSession.ensureSignedIn { signInResult ->
            signInResult.fold(
                onSuccess = {
                    val docId = MeshStateIds.docId(state.deviceIdHash, state.sequence)
                    val data = mapOf(
                        "device_id_hash" to MeshStateIds.deviceIdHashHex(state.deviceIdHash),
                        "status" to statusString(state.status),
                        "latitude" to state.latitudeE7 / 1e7,
                        "longitude" to state.longitudeE7 / 1e7,
                        "beacon_timestamp" to state.timestamp,
                        "sequence" to state.sequence,
                        "uploaded_at" to System.currentTimeMillis() / 1000,
                        // `arrayUnion` acumula sobre el documento existente
                        // (ADR-0002) con `merge: true`, en vez de arriesgarse
                        // a que un `set` sin merge lo trate como vacío.
                        "confirmed_by_gateways" to FieldValue.arrayUnion(Firebase.auth.currentUser?.uid ?: "")
                    )
                    Firebase.firestore.collection("mesh_states").document(docId)
                        .set(data, SetOptions.merge())
                        .addOnSuccessListener { onResult(Result.success(Unit)) }
                        .addOnFailureListener { error -> onResult(Result.failure(error)) }
                },
                onFailure = { error -> onResult(Result.failure(error)) }
            )
        }
    }

    private fun statusString(status: BeaconPacket.Status): String = when (status) {
        BeaconPacket.Status.SIN_CONFIRMAR -> "SIN_CONFIRMAR"
        BeaconPacket.Status.OK -> "OK"
        BeaconPacket.Status.AYUDA -> "AYUDA"
        BeaconPacket.Status.SILENCIO_TIMEOUT -> "SILENCIO_TIMEOUT"
        BeaconPacket.Status.GATEWAY_DISPONIBLE -> "GATEWAY_DISPONIBLE"
    }
}
