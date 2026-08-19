package com.farosos.app

import com.farosos.caseresolution.AttendingMark
import com.farosos.caseresolution.CaseResolutionIds
import com.farosos.caseresolution.CaseResolutionUploading
import com.farosos.caseresolution.ResolutionMark
import com.google.firebase.Firebase
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore

/**
 * Envoltorio de Firebase Auth (sesión anónima ya establecida, ADR-0003) +
 * Firestore para subir las marcas "resuelto"/"atendiendo" (#55) sobre
 * `mesh_states/{device_id_hash_víctima}_{secuencia_capturada}` — mismo
 * molde que `FirebaseMeshStateUploader`/`FirebaseIdentityConfirmationUploader`:
 * la clase concreta real vive sin tests en la capa de app. `device_id_hash`
 * y `sequence` viajan explícitos en ambas escrituras porque `isResolutionWrite()`
 * (`backend/firestore.rules`, #56) los necesita para validar la creación
 * parcial del documento cuando todavía no existe.
 */
class FirebaseCaseResolutionUploader : CaseResolutionUploading {
    override fun uploadResolved(mark: ResolutionMark, resolverDeviceIdHash: ByteArray, onResult: (Result<Unit>) -> Unit) {
        FirebaseAuthSession.ensureSignedIn { signInResult ->
            signInResult.fold(
                onSuccess = {
                    val docId = CaseResolutionIds.meshStateDocId(mark.victimDeviceIdHash, mark.victimSequence)
                    val data = mapOf(
                        "device_id_hash" to CaseResolutionIds.deviceIdHashHex(mark.victimDeviceIdHash),
                        "sequence" to mark.victimSequence,
                        "resuelto" to true,
                        "resuelto_por" to CaseResolutionIds.deviceIdHashHex(resolverDeviceIdHash),
                        "resuelto_en" to mark.markedAtEpochSeconds,
                        "resolutor_latitud_e7" to mark.resolverLatitudeE7,
                        "resolutor_longitud_e7" to mark.resolverLongitudeE7
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

    override fun uploadAttending(mark: AttendingMark, resolverDeviceIdHash: ByteArray, onResult: (Result<Unit>) -> Unit) {
        FirebaseAuthSession.ensureSignedIn { signInResult ->
            signInResult.fold(
                onSuccess = {
                    val docId = CaseResolutionIds.meshStateDocId(mark.victimDeviceIdHash, mark.victimSequence)
                    val entry = mapOf(
                        "device_id_hash" to CaseResolutionIds.deviceIdHashHex(resolverDeviceIdHash),
                        "marcado_en" to mark.markedAtEpochSeconds
                    )
                    val data = mapOf(
                        "device_id_hash" to CaseResolutionIds.deviceIdHashHex(mark.victimDeviceIdHash),
                        "sequence" to mark.victimSequence,
                        "atendido_por" to FieldValue.arrayUnion(entry)
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
}
