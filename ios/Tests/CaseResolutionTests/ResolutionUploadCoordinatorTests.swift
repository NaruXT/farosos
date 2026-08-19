import XCTest
@testable import CaseResolution

private final class FakeUploader: ResolutionUploading {
    private(set) var resolvedUploadCallCount = 0
    private(set) var attendingUploadCallCount = 0
    private(set) var lastResolvedMark: ResolvedMark?
    private(set) var lastAttendingMark: AttendingMark?
    private var pendingResolvedCompletion: ((Result<Void, Error>) -> Void)?
    private var pendingAttendingCompletion: ((Result<Void, Error>) -> Void)?

    func uploadResolved(_ mark: ResolvedMark, completion: @escaping (Result<Void, Error>) -> Void) {
        resolvedUploadCallCount += 1
        lastResolvedMark = mark
        pendingResolvedCompletion = completion
    }

    func uploadAttending(_ mark: AttendingMark, completion: @escaping (Result<Void, Error>) -> Void) {
        attendingUploadCallCount += 1
        lastAttendingMark = mark
        pendingAttendingCompletion = completion
    }

    func completeResolvedPending(_ result: Result<Void, Error>) {
        let completion = pendingResolvedCompletion
        pendingResolvedCompletion = nil
        completion?(result)
    }

    func completeAttendingPending(_ result: Result<Void, Error>) {
        let completion = pendingAttendingCompletion
        pendingAttendingCompletion = nil
        completion?(result)
    }
}

private struct UploadError: Error {}

final class ResolutionUploadCoordinatorTests: XCTestCase {
    private func makeResolvedMark(victim: UInt8 = 1, resolver: UInt8 = 9) -> ResolvedMark {
        ResolvedMark(
            victimDeviceIdHash: Data([victim]),
            victimSequence: 3,
            resolverDeviceIdHash: Data([resolver]),
            resolverLatitudeE7: 0,
            resolverLongitudeE7: 0,
            markedAt: 1_755_000_000
        )
    }

    private func makeAttendingMark(victim: UInt8 = 1, resolver: UInt8 = 9) -> AttendingMark {
        AttendingMark(
            victimDeviceIdHash: Data([victim]),
            victimSequence: 3,
            resolverDeviceIdHash: Data([resolver]),
            markedAt: 1_755_000_000
        )
    }

    func testConnectivityDetectedDoesNothingWithoutPendingMarks() {
        let uploader = FakeUploader()
        let coordinator = ResolutionUploadCoordinator(uploader: uploader)

        coordinator.connectivityDetected()

        XCTAssertEqual(uploader.resolvedUploadCallCount, 0)
        XCTAssertEqual(uploader.attendingUploadCallCount, 0)
    }

    func testConnectivityDetectedUploadsMarkPassedAtInit() {
        let uploader = FakeUploader()
        let mark = makeResolvedMark()
        let coordinator = ResolutionUploadCoordinator(uploader: uploader, pendingResolved: [mark])

        coordinator.connectivityDetected()

        XCTAssertEqual(uploader.resolvedUploadCallCount, 1)
        XCTAssertEqual(uploader.lastResolvedMark, mark)
    }

    func testMarkResolvedAddedAfterInitUploadsOnNextConnectivitySignal() {
        let uploader = FakeUploader()
        let coordinator = ResolutionUploadCoordinator(uploader: uploader)
        let mark = makeResolvedMark()

        coordinator.markResolved(mark)
        coordinator.connectivityDetected()

        XCTAssertEqual(uploader.resolvedUploadCallCount, 1)
        XCTAssertEqual(uploader.lastResolvedMark, mark)
    }

    func testSuccessfulResolvedUploadFiresCallbackAndStopsFurtherAttempts() {
        let uploader = FakeUploader()
        let mark = makeResolvedMark()
        let coordinator = ResolutionUploadCoordinator(uploader: uploader, pendingResolved: [mark])
        var uploaded: ResolvedMark?
        coordinator.onResolvedUploaded = { uploaded = $0 }

        coordinator.connectivityDetected()
        uploader.completeResolvedPending(.success(()))

        XCTAssertEqual(uploaded, mark)

        coordinator.connectivityDetected() // ya no queda nada pendiente, no debe reintentar

        XCTAssertEqual(uploader.resolvedUploadCallCount, 1)
    }

    func testFailedResolvedUploadKeepsMarkPendingForNextConnectivitySignal() {
        let uploader = FakeUploader()
        let mark = makeResolvedMark()
        let coordinator = ResolutionUploadCoordinator(uploader: uploader, pendingResolved: [mark])
        var uploaded: ResolvedMark?
        coordinator.onResolvedUploaded = { uploaded = $0 }

        coordinator.connectivityDetected()
        uploader.completeResolvedPending(.failure(UploadError()))

        XCTAssertNil(uploaded)

        coordinator.connectivityDetected() // reintenta porque el intento anterior falló

        XCTAssertEqual(uploader.resolvedUploadCallCount, 2)
    }

    func testConnectivityDetectedWhileUploadInFlightDoesNotDispatchTwice() {
        let uploader = FakeUploader()
        let mark = makeResolvedMark()
        let coordinator = ResolutionUploadCoordinator(uploader: uploader, pendingResolved: [mark])

        coordinator.connectivityDetected()
        coordinator.connectivityDetected() // el primer intento sigue en vuelo, no debe duplicar la subida

        XCTAssertEqual(uploader.resolvedUploadCallCount, 1)
    }

    func testMultiplePendingResolvedMarksDrainSequentiallyOnOneConnectivitySignal() {
        let uploader = FakeUploader()
        let markA = makeResolvedMark(victim: 1)
        let markB = makeResolvedMark(victim: 2)
        let coordinator = ResolutionUploadCoordinator(uploader: uploader, pendingResolved: [markA, markB])
        var uploaded: [ResolvedMark] = []
        coordinator.onResolvedUploaded = { uploaded.append($0) }

        coordinator.connectivityDetected()
        XCTAssertEqual(uploader.lastResolvedMark, markA)
        uploader.completeResolvedPending(.success(())) // drena sola hacia markB al completar

        XCTAssertEqual(uploader.resolvedUploadCallCount, 2)
        XCTAssertEqual(uploader.lastResolvedMark, markB)
        uploader.completeResolvedPending(.success(()))

        XCTAssertEqual(uploaded, [markA, markB])
    }

    func testAttendingMarkUploadsIndependentlyOfResolvedMarks() {
        let uploader = FakeUploader()
        let mark = makeAttendingMark()
        let coordinator = ResolutionUploadCoordinator(uploader: uploader, pendingAttending: [mark])
        var uploaded: AttendingMark?
        coordinator.onAttendingUploaded = { uploaded = $0 }

        coordinator.connectivityDetected()
        uploader.completeAttendingPending(.success(()))

        XCTAssertEqual(uploaded, mark)
        XCTAssertEqual(uploader.resolvedUploadCallCount, 0)
    }

    func testResolvedMarksDrainBeforeAttendingMarksInTheSameConnectivitySignal() {
        let uploader = FakeUploader()
        let resolved = makeResolvedMark()
        let attending = makeAttendingMark()
        let coordinator = ResolutionUploadCoordinator(uploader: uploader, pendingResolved: [resolved], pendingAttending: [attending])

        coordinator.connectivityDetected()

        XCTAssertEqual(uploader.resolvedUploadCallCount, 1)
        XCTAssertEqual(uploader.attendingUploadCallCount, 0, "atendiendo espera a que termine de drenar resuelto")

        uploader.completeResolvedPending(.success(()))

        XCTAssertEqual(uploader.attendingUploadCallCount, 1)
    }
}
