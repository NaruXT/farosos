package com.farosos.beaconradio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class FakeIdentityConfirmationUploader : IdentityConfirmationUploading {
    val uploadedDeviceIdHashes = mutableListOf<ByteArray>()
    private var pendingOnResult: ((Result<Unit>) -> Unit)? = null

    override fun upload(deviceIdHash: ByteArray, onResult: (Result<Unit>) -> Unit) {
        uploadedDeviceIdHashes.add(deviceIdHash)
        pendingOnResult = onResult
    }

    fun completePending(result: Result<Unit>) {
        val onResult = pendingOnResult
        pendingOnResult = null
        onResult?.invoke(result)
    }
}

private class IdentityUploadError : Exception()

class IdentityConfirmationUploaderTest {
    @Test
    fun startUploadsExistingSnapshotFromRegistry() {
        val registry = VerifiedIdentityRegistry()
        val hash = byteArrayOf(1, 2, 3, 4, 5, 6)
        registry.record(hash)
        val uploader = FakeIdentityConfirmationUploader()
        val identityUploader = IdentityConfirmationUploader(registry, uploader)

        identityUploader.start()

        assertEquals(1, uploader.uploadedDeviceIdHashes.size)
        assertTrue(hash.contentEquals(uploader.uploadedDeviceIdHashes.first()))
    }

    @Test
    fun doesNotUploadAnythingBeforeStart() {
        val registry = VerifiedIdentityRegistry()
        val uploader = FakeIdentityConfirmationUploader()
        IdentityConfirmationUploader(registry, uploader)

        registry.record(byteArrayOf(1, 2, 3, 4, 5, 6))

        assertEquals(0, uploader.uploadedDeviceIdHashes.size)
    }

    @Test
    fun uploadsIncrementalIdentitiesWhileActive() {
        val registry = VerifiedIdentityRegistry()
        val uploader = FakeIdentityConfirmationUploader()
        val identityUploader = IdentityConfirmationUploader(registry, uploader)
        identityUploader.start()

        val deviceA = byteArrayOf(1, 1, 1, 1, 1, 1)
        val deviceB = byteArrayOf(2, 2, 2, 2, 2, 2)
        registry.record(deviceA)
        registry.record(deviceB)

        assertEquals(2, uploader.uploadedDeviceIdHashes.size)
        assertTrue(deviceA.contentEquals(uploader.uploadedDeviceIdHashes[0]))
        assertTrue(deviceB.contentEquals(uploader.uploadedDeviceIdHashes[1]))
    }

    @Test
    fun stopsUploadingAfterStop() {
        val registry = VerifiedIdentityRegistry()
        val uploader = FakeIdentityConfirmationUploader()
        val identityUploader = IdentityConfirmationUploader(registry, uploader)
        identityUploader.start()

        identityUploader.stop()
        registry.record(byteArrayOf(1, 2, 3, 4, 5, 6))

        assertEquals(0, uploader.uploadedDeviceIdHashes.size)
    }

    @Test
    fun uploadFailureFiresOnErrorWithoutCrashing() {
        val registry = VerifiedIdentityRegistry()
        registry.record(byteArrayOf(1, 2, 3, 4, 5, 6))
        val uploader = FakeIdentityConfirmationUploader()
        val identityUploader = IdentityConfirmationUploader(registry, uploader)
        var observedError: Throwable? = null
        identityUploader.onError = { observedError = it }

        identityUploader.start()
        uploader.completePending(Result.failure(IdentityUploadError()))

        assertNotNull(observedError)
    }
}
