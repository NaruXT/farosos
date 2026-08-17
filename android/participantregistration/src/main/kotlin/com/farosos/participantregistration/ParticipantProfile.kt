package com.farosos.participantregistration

/**
 * Datos de identidad opt-in recogidos en el registro de la primera apertura
 * (ADR-0003) — nombre obligatorio, contacto opcional.
 */
data class ParticipantProfile(val name: String, val contact: String?)
