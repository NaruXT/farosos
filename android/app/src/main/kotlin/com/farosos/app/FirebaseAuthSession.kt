package com.farosos.app

import com.google.firebase.Firebase
import com.google.firebase.auth.auth

/**
 * Sesión anónima compartida por los distintos uploaders de Firebase
 * (`FirebaseParticipantUploader` #30, `FirebaseMeshStateUploader` #32) — una
 * sola por instalación (ADR-0003), sin volver a firmarse si ya hay una
 * activa.
 */
object FirebaseAuthSession {
    fun ensureSignedIn(onResult: (Result<Unit>) -> Unit) {
        val auth = Firebase.auth
        if (auth.currentUser != null) {
            onResult(Result.success(Unit))
            return
        }
        auth.signInAnonymously()
            .addOnSuccessListener { onResult(Result.success(Unit)) }
            .addOnFailureListener { error -> onResult(Result.failure(error)) }
    }
}
