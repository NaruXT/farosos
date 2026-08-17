package com.farosos.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.farosos.participantregistration.ParticipantProfile

/**
 * Guarda el perfil localmente y nada más — la subida real ocurre después,
 * disparada por `ConnectivityMonitor` dentro de `EmergencyViewModel`
 * (ADR-0003: desacoplada de este flujo, para que "continuar" nunca
 * requiera conectividad).
 */
class RegistrationViewModel(application: Application) : AndroidViewModel(application) {
    var name by mutableStateOf("")
    var contact by mutableStateOf("")

    val canContinue: Boolean
        get() = name.trim().isNotEmpty()

    fun completeRegistration() {
        val trimmedContact = contact.trim()
        val profile = ParticipantProfile(name = name.trim(), contact = trimmedContact.ifEmpty { null })
        ParticipantStore.save(profile, getApplication())
    }
}
