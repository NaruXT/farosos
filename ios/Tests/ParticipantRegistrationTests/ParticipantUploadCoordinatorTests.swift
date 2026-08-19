import XCTest
@testable import ParticipantRegistration

private final class FakeUploader: ParticipantUploading {
    private(set) var uploadCallCount = 0
    private(set) var lastDeviceIdHash: Data?
    private(set) var lastPublicKeyEd25519: Data?
    private(set) var lastProfile: ParticipantProfile?
    private var pendingCompletion: ((Result<Void, Error>) -> Void)?

    func upload(deviceIdHash: Data, publicKeyEd25519: Data, profile: ParticipantProfile, completion: @escaping (Result<Void, Error>) -> Void) {
        uploadCallCount += 1
        lastDeviceIdHash = deviceIdHash
        lastPublicKeyEd25519 = publicKeyEd25519
        lastProfile = profile
        pendingCompletion = completion
    }

    func completePending(_ result: Result<Void, Error>) {
        let completion = pendingCompletion
        pendingCompletion = nil
        completion?(result)
    }
}

private struct UploadError: Error {}

final class ParticipantUploadCoordinatorTests: XCTestCase {
    private let deviceIdHash = Data([0x01, 0x02, 0x03])
    private let publicKeyEd25519 = Data(repeating: 0xAB, count: 32)

    func testConnectivityDetectedDoesNothingWithoutPendingProfile() {
        let uploader = FakeUploader()
        let coordinator = ParticipantUploadCoordinator(deviceIdHash: deviceIdHash, publicKeyEd25519: publicKeyEd25519, uploader: uploader)

        coordinator.connectivityDetected()

        XCTAssertEqual(uploader.uploadCallCount, 0)
    }

    func testConnectivityDetectedUploadsProfilePassedAtInit() {
        let uploader = FakeUploader()
        let profile = ParticipantProfile(name: "Ana", contact: "+51999999999")
        let coordinator = ParticipantUploadCoordinator(deviceIdHash: deviceIdHash, publicKeyEd25519: publicKeyEd25519, uploader: uploader, pendingProfile: profile)

        coordinator.connectivityDetected()

        XCTAssertEqual(uploader.uploadCallCount, 1)
        XCTAssertEqual(uploader.lastDeviceIdHash, deviceIdHash)
        XCTAssertEqual(uploader.lastPublicKeyEd25519, publicKeyEd25519)
        XCTAssertEqual(uploader.lastProfile, profile)
    }

    func testSuccessfulUploadFiresCallbackAndStopsFurtherAttempts() {
        let uploader = FakeUploader()
        let profile = ParticipantProfile(name: "Ana", contact: nil)
        let coordinator = ParticipantUploadCoordinator(deviceIdHash: deviceIdHash, publicKeyEd25519: publicKeyEd25519, uploader: uploader, pendingProfile: profile)
        var succeeded = false
        coordinator.onUploadSucceeded = { succeeded = true }

        coordinator.connectivityDetected()
        uploader.completePending(.success(()))

        XCTAssertTrue(succeeded)

        coordinator.connectivityDetected() // ya no queda nada pendiente, no debe reintentar

        XCTAssertEqual(uploader.uploadCallCount, 1)
    }

    func testFailedUploadKeepsProfilePendingForNextConnectivitySignal() {
        let uploader = FakeUploader()
        let profile = ParticipantProfile(name: "Ana", contact: nil)
        let coordinator = ParticipantUploadCoordinator(deviceIdHash: deviceIdHash, publicKeyEd25519: publicKeyEd25519, uploader: uploader, pendingProfile: profile)
        var succeeded = false
        coordinator.onUploadSucceeded = { succeeded = true }

        coordinator.connectivityDetected()
        uploader.completePending(.failure(UploadError()))

        XCTAssertFalse(succeeded)

        coordinator.connectivityDetected() // reintenta porque el intento anterior falló

        XCTAssertEqual(uploader.uploadCallCount, 2)
    }

    func testConnectivityDetectedWhileUploadInFlightDoesNotDispatchTwice() {
        let uploader = FakeUploader()
        let profile = ParticipantProfile(name: "Ana", contact: nil)
        let coordinator = ParticipantUploadCoordinator(deviceIdHash: deviceIdHash, publicKeyEd25519: publicKeyEd25519, uploader: uploader, pendingProfile: profile)

        coordinator.connectivityDetected()
        coordinator.connectivityDetected() // el primer intento sigue en vuelo, no debe duplicar la subida

        XCTAssertEqual(uploader.uploadCallCount, 1)
    }
}
