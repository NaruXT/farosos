package com.farosos.app

import com.farosos.beaconradio.IdentityConfirmationUploading
import com.farosos.participantregistration.ParticipantIds
import com.google.firebase.Firebase
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore

/**
 * Envoltorio de Firebase Auth (reutiliza la sesión anónima ya establecida
 * en el registro de identidad, #30) + Firestore para subir el campo
 * `identidad_verificada_caso_a` de `participants/{device_id_hash}` (ticket
 * #53). Sin interfaz propia más allá de `IdentityConfirmationUploading`
 * (que sí vive en el módulo testeado) — mismo molde que
 * `FirebaseMeshStateUploader`/`FirebaseParticipantUploader`: la clase
 * concreta real vive sin tests en la capa de app.
 */
class FirebaseIdentityConfirmationUploader : IdentityConfirmationUploading {
    override fun upload(deviceIdHash: ByteArray, onResult: (Result<Unit>) -> Unit) {
        FirebaseAuthSession.ensureSignedIn { signInResult ->
            signInResult.fold(
                onSuccess = {
                    val hashHex = ParticipantIds.deviceIdHashHex(deviceIdHash)
                    val data = mapOf(
                        "device_id_hash" to hashHex,
                        "identidad_verificada_caso_a" to true
                    )
                    // `SetOptions.merge()` — a diferencia de `FirebaseParticipantUploader`
                    // (que sube el perfil completo del propio dueño) — es
                    // obligatorio acá: este documento puede pertenecer a OTRO
                    // dispositivo que ya tiene `name`/`contacto` subidos por su
                    // propio registro (o los suba después); un `set` sin merge
                    // los borraría (AC de #53).
                    Firebase.firestore.collection("participants").document(hashHex)
                        .set(data, SetOptions.merge())
                        .addOnSuccessListener { onResult(Result.success(Unit)) }
                        .addOnFailureListener { error -> onResult(Result.failure(error)) }
                },
                onFailure = { error -> onResult(Result.failure(error)) }
            )
        }
    }
}
