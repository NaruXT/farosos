package com.farosos.beaconradio

import com.farosos.codec.BeaconPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private class FakeMeshStateUploader : MeshStateUploading {
    val upsertedStates = mutableListOf<MeshParticipantState>()
    private var pendingOnResult: ((Result<Unit>) -> Unit)? = null

    override fun upsert(state: MeshParticipantState, onResult: (Result<Unit>) -> Unit) {
        upsertedStates.add(state)
        pendingOnResult = onResult
    }

    fun completePending(result: Result<Unit>) {
        val onResult = pendingOnResult
        pendingOnResult = null
        onResult?.invoke(result)
    }
}

private class UploadError : Exception()

class GatewayUploaderTest {
    private fun makePacket(
        deviceIdHash: ByteArray = byteArrayOf(1, 2, 3, 4, 5, 6),
        sequence: Int
    ): BeaconPacket = BeaconPacket(
        messageType = BeaconPacket.MessageType.BEACON,
        deviceIdHash = deviceIdHash,
        status = BeaconPacket.Status.OK,
        latitudeE7 = 0,
        longitudeE7 = 0,
        timestamp = 1_700_000_000L,
        ttl = 16,
        nonce = 1,
        sequence = sequence
    )

    @Test
    fun startUploadsExistingSnapshotFromRegistry() {
        val registry = MeshStateRegistry()
        registry.update(makePacket(sequence = 1))
        val uploader = FakeMeshStateUploader()
        val gatewayUploader = GatewayUploader(registry, uploader)

        gatewayUploader.start()

        assertEquals(1, uploader.upsertedStates.size)
        assertEquals(1, uploader.upsertedStates.first().sequence)
    }

    @Test
    fun doesNotUploadAnythingBeforeStart() {
        val registry = MeshStateRegistry()
        val uploader = FakeMeshStateUploader()
        GatewayUploader(registry, uploader)

        registry.update(makePacket(sequence = 1))

        assertEquals(0, uploader.upsertedStates.size)
    }

    @Test
    fun uploadsIncrementalUpdatesWhileActive() {
        val registry = MeshStateRegistry()
        val uploader = FakeMeshStateUploader()
        val gatewayUploader = GatewayUploader(registry, uploader)
        gatewayUploader.start()

        registry.update(makePacket(sequence = 1))
        registry.update(makePacket(sequence = 2))

        assertEquals(listOf(1, 2), uploader.upsertedStates.map { it.sequence })
    }

    @Test
    fun stopsUploadingAfterStop() {
        val registry = MeshStateRegistry()
        val uploader = FakeMeshStateUploader()
        val gatewayUploader = GatewayUploader(registry, uploader)
        gatewayUploader.start()

        gatewayUploader.stop()
        registry.update(makePacket(sequence = 1))

        assertEquals(0, uploader.upsertedStates.size)
    }

    @Test
    fun uploadFailureFiresOnErrorWithoutCrashing() {
        val registry = MeshStateRegistry()
        registry.update(makePacket(sequence = 1))
        val uploader = FakeMeshStateUploader()
        val gatewayUploader = GatewayUploader(registry, uploader)
        var observedError: Throwable? = null
        gatewayUploader.onError = { observedError = it }

        gatewayUploader.start()
        uploader.completePending(Result.failure(UploadError()))

        assertNotNull(observedError)
    }
}
