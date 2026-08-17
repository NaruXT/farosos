package com.farosos.app

import com.farosos.participantregistration.ParticipantIds
import com.farosos.participantregistration.ParticipantProfile
import com.farosos.participantregistration.ParticipantUploading
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore

/**
 * Envoltorio de Firebase Auth (sesión anónima, una por instalación,
 * ADR-0003) + Firestore para subir `participants/{device_id_hash}`. Sin
 * interfaz propia más allá de `ParticipantUploading` (que sí vive en el
 * módulo testeado) — mismo molde que `BleAdvertiser`/`ConnectivityMonitor`:
 * la clase concreta real vive sin tests en la capa de app.
 */
class FirebaseParticipantUploader : ParticipantUploading {
    override fun upload(deviceIdHash: ByteArray, profile: ParticipantProfile, onResult: (Result<Unit>) -> Unit) {
        FirebaseAuthSession.ensureSignedIn { signInResult ->
            signInResult.fold(
                onSuccess = {
                    val hashHex = ParticipantIds.deviceIdHashHex(deviceIdHash)
                    val data = mutableMapOf<String, Any>(
                        "device_id_hash" to hashHex,
                        "name" to profile.name
                    )
                    profile.contact?.let { data["contacto"] = it }
                    Firebase.firestore.collection("participants").document(hashHex).set(data)
                        .addOnSuccessListener { onResult(Result.success(Unit)) }
                        .addOnFailureListener { error -> onResult(Result.failure(error)) }
                },
                onFailure = { error -> onResult(Result.failure(error)) }
            )
        }
    }
}
