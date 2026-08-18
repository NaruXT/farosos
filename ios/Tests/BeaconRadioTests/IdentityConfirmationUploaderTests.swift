import XCTest
@testable import BeaconRadio

private final class FakeIdentityConfirmationUploader: IdentityConfirmationUploading {
    private(set) var uploadedDeviceIdHashes: [Data] = []
    private var pendingCompletion: ((Result<Void, Error>) -> Void)?

    func upload(deviceIdHash: Data, completion: @escaping (Result<Void, Error>) -> Void) {
        uploadedDeviceIdHashes.append(deviceIdHash)
        pendingCompletion = completion
    }

    func completePending(_ result: Result<Void, Error>) {
        let completion = pendingCompletion
        pendingCompletion = nil
        completion?(result)
    }
}

private struct UploadError: Error {}

final class IdentityConfirmationUploaderTests: XCTestCase {
    func testStartUploadsExistingSnapshotFromRegistry() {
        let registry = VerifiedIdentityRegistry()
        let hash = Data([1, 2, 3, 4, 5, 6])
        registry.record(hash)
        let uploader = FakeIdentityConfirmationUploader()
        let identityUploader = IdentityConfirmationUploader(registry: registry, uploader: uploader)

        identityUploader.start()

        XCTAssertEqual(uploader.uploadedDeviceIdHashes, [hash])
    }

    func testDoesNotUploadAnythingBeforeStart() {
        let registry = VerifiedIdentityRegistry()
        let uploader = FakeIdentityConfirmationUploader()
        _ = IdentityConfirmationUploader(registry: registry, uploader: uploader)

        registry.record(Data([1, 2, 3, 4, 5, 6]))

        XCTAssertEqual(uploader.uploadedDeviceIdHashes.count, 0)
    }

    func testUploadsIncrementalIdentitiesWhileActive() {
        let registry = VerifiedIdentityRegistry()
        let uploader = FakeIdentityConfirmationUploader()
        let identityUploader = IdentityConfirmationUploader(registry: registry, uploader: uploader)
        identityUploader.start()

        let deviceA = Data([1, 1, 1, 1, 1, 1])
        let deviceB = Data([2, 2, 2, 2, 2, 2])
        registry.record(deviceA)
        registry.record(deviceB)

        XCTAssertEqual(uploader.uploadedDeviceIdHashes, [deviceA, deviceB])
    }

    func testStopsUploadingAfterStop() {
        let registry = VerifiedIdentityRegistry()
        let uploader = FakeIdentityConfirmationUploader()
        let identityUploader = IdentityConfirmationUploader(registry: registry, uploader: uploader)
        identityUploader.start()

        identityUploader.stop()
        registry.record(Data([1, 2, 3, 4, 5, 6]))

        XCTAssertEqual(uploader.uploadedDeviceIdHashes.count, 0)
    }

    func testUploadFailureFiresOnErrorWithoutCrashing() {
        let registry = VerifiedIdentityRegistry()
        registry.record(Data([1, 2, 3, 4, 5, 6]))
        let uploader = FakeIdentityConfirmationUploader()
        let identityUploader = IdentityConfirmationUploader(registry: registry, uploader: uploader)
        var observedError: Error?
        identityUploader.onError = { observedError = $0 }

        identityUploader.start()
        uploader.completePending(.failure(UploadError()))

        XCTAssertNotNil(observedError)
    }
}
