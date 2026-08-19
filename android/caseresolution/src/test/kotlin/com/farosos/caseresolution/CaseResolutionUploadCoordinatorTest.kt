package com.farosos.caseresolution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeUploader : CaseResolutionUploading {
    var resolvedUploadCallCount = 0
        private set
    var attendingUploadCallCount = 0
        private set
    var lastResolverDeviceIdHash: ByteArray? = null
        private set
    private val pendingResolved = mutableMapOf<ResolutionMark, (Result<Unit>) -> Unit>()
    private val pendingAttending = mutableMapOf<AttendingMark, (Result<Unit>) -> Unit>()

    override fun uploadResolved(mark: ResolutionMark, resolverDeviceIdHash: ByteArray, onResult: (Result<Unit>) -> Unit) {
        resolvedUploadCallCount += 1
        lastResolverDeviceIdHash = resolverDeviceIdHash
        pendingResolved[mark] = onResult
    }

    override fun uploadAttending(mark: AttendingMark, resolverDeviceIdHash: ByteArray, onResult: (Result<Unit>) -> Unit) {
        attendingUploadCallCount += 1
        lastResolverDeviceIdHash = resolverDeviceIdHash
        pendingAttending[mark] = onResult
    }

    fun completeResolved(mark: ResolutionMark, result: Result<Unit>) {
        pendingResolved.remove(mark)?.invoke(result)
    }

    fun completeAttending(mark: AttendingMark, result: Result<Unit>) {
        pendingAttending.remove(mark)?.invoke(result)
    }
}

private class UploadException : Exception()

class CaseResolutionUploadCoordinatorTest {
    private val resolverDeviceIdHash = byteArrayOf(0x0A, 0x0B, 0x0C)
    private val victimDeviceIdHash = byteArrayOf(0x01, 0x02, 0x03)

    private fun resolutionMark(sequence: Int = 3) = ResolutionMark(
        victimDeviceIdHash = victimDeviceIdHash,
        victimSequence = sequence,
        resolverLatitudeE7 = 0,
        resolverLongitudeE7 = 0,
        markedAtEpochSeconds = 1755000000L
    )

    private fun attendingMark(sequence: Int = 3) = AttendingMark(
        victimDeviceIdHash = victimDeviceIdHash,
        victimSequence = sequence,
        markedAtEpochSeconds = 1755000000L
    )

    // --- "resuelto" ---

    @Test
    fun markResolvedUploadsImmediately() {
        val uploader = FakeUploader()
        val coordinator = CaseResolutionUploadCoordinator(resolverDeviceIdHash, uploader)
        val mark = resolutionMark()

        coordinator.markResolved(mark)

        assertEquals(1, uploader.resolvedUploadCallCount)
        assertEquals(resolverDeviceIdHash, uploader.lastResolverDeviceIdHash)
    }

    @Test
    fun successfulResolutionUploadFiresCallbackAndStopsFurtherAttempts() {
        val uploader = FakeUploader()
        val coordinator = CaseResolutionUploadCoordinator(resolverDeviceIdHash, uploader)
        val mark = resolutionMark()
        var uploaded: ResolutionMark? = null
        coordinator.onResolutionUploaded = { uploaded = it }

        coordinator.markResolved(mark)
        uploader.completeResolved(mark, Result.success(Unit))

        assertEquals(mark, uploaded)

        coordinator.connectivityDetected() // ya no queda nada pendiente, no debe reintentar

        assertEquals(1, uploader.resolvedUploadCallCount)
    }

    @Test
    fun failedResolutionUploadKeepsMarkPendingForNextConnectivitySignal() {
        val uploader = FakeUploader()
        val coordinator = CaseResolutionUploadCoordinator(resolverDeviceIdHash, uploader)
        val mark = resolutionMark()
        var uploaded = false
        coordinator.onResolutionUploaded = { uploaded = true }

        coordinator.markResolved(mark)
        uploader.completeResolved(mark, Result.failure(UploadException()))

        assertFalse(uploaded)

        coordinator.connectivityDetected() // reintenta porque el intento anterior falló

        assertEquals(2, uploader.resolvedUploadCallCount)
    }

    @Test
    fun markResolvedWhileUploadInFlightDoesNotDispatchTwice() {
        val uploader = FakeUploader()
        val coordinator = CaseResolutionUploadCoordinator(resolverDeviceIdHash, uploader)
        val mark = resolutionMark()

        coordinator.markResolved(mark)
        coordinator.connectivityDetected() // el intento anterior sigue en vuelo, no debe duplicar la subida

        assertEquals(1, uploader.resolvedUploadCallCount)
    }

    @Test
    fun multiplePendingResolutionsUploadIndependently() {
        val uploader = FakeUploader()
        val coordinator = CaseResolutionUploadCoordinator(resolverDeviceIdHash, uploader)
        val markA = resolutionMark(sequence = 3)
        val markB = resolutionMark(sequence = 5)

        coordinator.markResolved(markA)
        coordinator.markResolved(markB)

        assertEquals(2, uploader.resolvedUploadCallCount)

        uploader.completeResolved(markA, Result.success(Unit))
        uploader.completeResolved(markB, Result.failure(UploadException()))
        coordinator.connectivityDetected() // solo debe reintentar markB, markA ya subió

        assertEquals(3, uploader.resolvedUploadCallCount)
    }

    @Test
    fun constructorPendingResolutionsOnlyUploadOnConnectivityDetected() {
        val uploader = FakeUploader()
        val mark = resolutionMark()
        val coordinator = CaseResolutionUploadCoordinator(resolverDeviceIdHash, uploader, pendingResolutions = listOf(mark))

        assertEquals(0, uploader.resolvedUploadCallCount)

        coordinator.connectivityDetected()

        assertEquals(1, uploader.resolvedUploadCallCount)
    }

    // --- "atendiendo" ---

    @Test
    fun markAttendingUploadsImmediately() {
        val uploader = FakeUploader()
        val coordinator = CaseResolutionUploadCoordinator(resolverDeviceIdHash, uploader)
        val mark = attendingMark()

        coordinator.markAttending(mark)

        assertEquals(1, uploader.attendingUploadCallCount)
        assertEquals(resolverDeviceIdHash, uploader.lastResolverDeviceIdHash)
    }

    @Test
    fun successfulAttendingUploadFiresCallback() {
        val uploader = FakeUploader()
        val coordinator = CaseResolutionUploadCoordinator(resolverDeviceIdHash, uploader)
        val mark = attendingMark()
        var uploaded: AttendingMark? = null
        coordinator.onAttendingUploaded = { uploaded = it }

        coordinator.markAttending(mark)
        uploader.completeAttending(mark, Result.success(Unit))

        assertEquals(mark, uploaded)
    }

    @Test
    fun failedAttendingUploadKeepsMarkPendingForNextConnectivitySignal() {
        val uploader = FakeUploader()
        val coordinator = CaseResolutionUploadCoordinator(resolverDeviceIdHash, uploader)
        val mark = attendingMark()

        coordinator.markAttending(mark)
        uploader.completeAttending(mark, Result.failure(UploadException()))
        coordinator.connectivityDetected()

        assertEquals(2, uploader.attendingUploadCallCount)
    }

    @Test
    fun successfulAttendingUploadStopsFurtherAttempts() {
        val uploader = FakeUploader()
        val coordinator = CaseResolutionUploadCoordinator(resolverDeviceIdHash, uploader)
        val mark = attendingMark()

        coordinator.markAttending(mark)
        uploader.completeAttending(mark, Result.success(Unit))
        coordinator.connectivityDetected() // ya no queda nada pendiente, no debe reintentar

        assertEquals(1, uploader.attendingUploadCallCount)
    }

    @Test
    fun multiplePendingAttendingMarksUploadIndependently() {
        val uploader = FakeUploader()
        val coordinator = CaseResolutionUploadCoordinator(resolverDeviceIdHash, uploader)
        val markA = attendingMark(sequence = 3)
        val markB = attendingMark(sequence = 5)

        coordinator.markAttending(markA)
        coordinator.markAttending(markB)

        assertEquals(2, uploader.attendingUploadCallCount)

        uploader.completeAttending(markA, Result.success(Unit))
        uploader.completeAttending(markB, Result.failure(UploadException()))
        coordinator.connectivityDetected() // solo debe reintentar markB, markA ya subió

        assertEquals(3, uploader.attendingUploadCallCount)
    }

    @Test
    fun resolvedAndAttendingPendingListsAreIndependent() {
        val uploader = FakeUploader()
        val coordinator = CaseResolutionUploadCoordinator(resolverDeviceIdHash, uploader)
        val resolved = resolutionMark()
        val attending = attendingMark()

        coordinator.markResolved(resolved)
        uploader.completeResolved(resolved, Result.failure(UploadException()))
        coordinator.markAttending(attending)
        uploader.completeAttending(attending, Result.success(Unit))

        coordinator.connectivityDetected() // solo debe reintentar la resolución fallida

        assertEquals(2, uploader.resolvedUploadCallCount)
        assertEquals(1, uploader.attendingUploadCallCount)
    }
}
