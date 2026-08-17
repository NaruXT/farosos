import XCTest
import PacketCodec
@testable import BeaconRadio

private final class FakeMeshStateUploader: MeshStateUploading {
    private(set) var upsertedStates: [MeshParticipantState] = []
    private var pendingCompletion: ((Result<Void, Error>) -> Void)?

    func upsert(_ state: MeshParticipantState, completion: @escaping (Result<Void, Error>) -> Void) {
        upsertedStates.append(state)
        pendingCompletion = completion
    }

    func completePending(_ result: Result<Void, Error>) {
        let completion = pendingCompletion
        pendingCompletion = nil
        completion?(result)
    }
}

private struct UploadError: Error {}

final class GatewayUploaderTests: XCTestCase {
    private func makePacket(deviceIdHash: Data = Data([1, 2, 3, 4, 5, 6]), sequence: UInt8) -> BeaconPacket {
        BeaconPacket(
            messageType: .beacon,
            deviceIdHash: deviceIdHash,
            status: .ok,
            latitudeE7: 0,
            longitudeE7: 0,
            timestamp: 1_700_000_000,
            ttl: 16,
            nonce: 1,
            sequence: sequence
        )
    }

    func testStartUploadsExistingSnapshotFromRegistry() {
        let registry = MeshStateRegistry()
        registry.update(with: makePacket(sequence: 1))
        let uploader = FakeMeshStateUploader()
        let gatewayUploader = GatewayUploader(registry: registry, uploader: uploader)

        gatewayUploader.start()

        XCTAssertEqual(uploader.upsertedStates.count, 1)
        XCTAssertEqual(uploader.upsertedStates.first?.sequence, 1)
    }

    func testDoesNotUploadAnythingBeforeStart() {
        let registry = MeshStateRegistry()
        let uploader = FakeMeshStateUploader()
        _ = GatewayUploader(registry: registry, uploader: uploader)

        registry.update(with: makePacket(sequence: 1))

        XCTAssertEqual(uploader.upsertedStates.count, 0)
    }

    func testUploadsIncrementalUpdatesWhileActive() {
        let registry = MeshStateRegistry()
        let uploader = FakeMeshStateUploader()
        let gatewayUploader = GatewayUploader(registry: registry, uploader: uploader)
        gatewayUploader.start()

        registry.update(with: makePacket(sequence: 1))
        registry.update(with: makePacket(sequence: 2))

        XCTAssertEqual(uploader.upsertedStates.map(\.sequence), [1, 2])
    }

    func testStopsUploadingAfterStop() {
        let registry = MeshStateRegistry()
        let uploader = FakeMeshStateUploader()
        let gatewayUploader = GatewayUploader(registry: registry, uploader: uploader)
        gatewayUploader.start()

        gatewayUploader.stop()
        registry.update(with: makePacket(sequence: 1))

        XCTAssertEqual(uploader.upsertedStates.count, 0)
    }

    func testUploadFailureFiresOnErrorWithoutCrashing() {
        let registry = MeshStateRegistry()
        registry.update(with: makePacket(sequence: 1))
        let uploader = FakeMeshStateUploader()
        let gatewayUploader = GatewayUploader(registry: registry, uploader: uploader)
        var observedError: Error?
        gatewayUploader.onError = { observedError = $0 }

        gatewayUploader.start()
        uploader.completePending(.failure(UploadError()))

        XCTAssertNotNil(observedError)
    }
}
