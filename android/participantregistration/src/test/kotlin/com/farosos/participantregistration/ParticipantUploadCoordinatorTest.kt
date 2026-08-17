package com.farosos.participantregistration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeUploader : ParticipantUploading {
    var uploadCallCount = 0
        private set
    var lastDeviceIdHash: ByteArray? = null
        private set
    var lastProfile: ParticipantProfile? = null
        private set
    private var pendingOnResult: ((Result<Unit>) -> Unit)? = null

    override fun upload(deviceIdHash: ByteArray, profile: ParticipantProfile, onResult: (Result<Unit>) -> Unit) {
        uploadCallCount += 1
        lastDeviceIdHash = deviceIdHash
        lastProfile = profile
        pendingOnResult = onResult
    }

    fun completePending(result: Result<Unit>) {
        val onResult = pendingOnResult
        pendingOnResult = null
        onResult?.invoke(result)
    }
}

private class UploadException : Exception()

class ParticipantUploadCoordinatorTest {
    private val deviceIdHash = byteArrayOf(0x01, 0x02, 0x03)

    @Test
    fun connectivityDetectedDoesNothingWithoutPendingProfile() {
        val uploader = FakeUploader()
        val coordinator = ParticipantUploadCoordinator(deviceIdHash, uploader)

        coordinator.connectivityDetected()

        assertEquals(0, uploader.uploadCallCount)
    }

    @Test
    fun connectivityDetectedUploadsProfilePassedAtInit() {
        val uploader = FakeUploader()
        val profile = ParticipantProfile(name = "Ana", contact = "+51999999999")
        val coordinator = ParticipantUploadCoordinator(deviceIdHash, uploader, pendingProfile = profile)

        coordinator.connectivityDetected()

        assertEquals(1, uploader.uploadCallCount)
        assertEquals(deviceIdHash, uploader.lastDeviceIdHash)
        assertEquals(profile, uploader.lastProfile)
    }

    @Test
    fun successfulUploadFiresCallbackAndStopsFurtherAttempts() {
        val uploader = FakeUploader()
        val profile = ParticipantProfile(name = "Ana", contact = null)
        val coordinator = ParticipantUploadCoordinator(deviceIdHash, uploader, pendingProfile = profile)
        var succeeded = false
        coordinator.onUploadSucceeded = { succeeded = true }

        coordinator.connectivityDetected()
        uploader.completePending(Result.success(Unit))

        assertTrue(succeeded)

        coordinator.connectivityDetected() // ya no queda nada pendiente, no debe reintentar

        assertEquals(1, uploader.uploadCallCount)
    }

    @Test
    fun failedUploadKeepsProfilePendingForNextConnectivitySignal() {
        val uploader = FakeUploader()
        val profile = ParticipantProfile(name = "Ana", contact = null)
        val coordinator = ParticipantUploadCoordinator(deviceIdHash, uploader, pendingProfile = profile)
        var succeeded = false
        coordinator.onUploadSucceeded = { succeeded = true }

        coordinator.connectivityDetected()
        uploader.completePending(Result.failure(UploadException()))

        assertFalse(succeeded)

        coordinator.connectivityDetected() // reintenta porque el intento anterior falló

        assertEquals(2, uploader.uploadCallCount)
    }

    @Test
    fun connectivityDetectedWhileUploadInFlightDoesNotDispatchTwice() {
        val uploader = FakeUploader()
        val profile = ParticipantProfile(name = "Ana", contact = null)
        val coordinator = ParticipantUploadCoordinator(deviceIdHash, uploader, pendingProfile = profile)

        coordinator.connectivityDetected()
        coordinator.connectivityDetected() // el primer intento sigue en vuelo, no debe duplicar la subida

        assertEquals(1, uploader.uploadCallCount)
    }
}
