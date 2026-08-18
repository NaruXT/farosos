import XCTest
@testable import BeaconRadio

final class VerifiedIdentityRegistryTests: XCTestCase {
    func testRecordStoresFirstDeviceIdHashSeen() {
        let registry = VerifiedIdentityRegistry()
        let hash = Data([1, 2, 3, 4, 5, 6])

        let accepted = registry.record(hash)

        XCTAssertTrue(accepted)
        XCTAssertEqual(registry.allDeviceIdHashes(), [hash])
    }

    func testRecordRejectsTheSameDeviceIdHashTwice() {
        let registry = VerifiedIdentityRegistry()
        let hash = Data([1, 2, 3, 4, 5, 6])
        registry.record(hash)

        let acceptedAgain = registry.record(hash)

        XCTAssertFalse(acceptedAgain)
        XCTAssertEqual(registry.allDeviceIdHashes(), [hash])
    }

    func testRecordTracksMultipleDevicesIndependently() {
        let registry = VerifiedIdentityRegistry()
        let deviceA = Data([1, 1, 1, 1, 1, 1])
        let deviceB = Data([2, 2, 2, 2, 2, 2])

        registry.record(deviceA)
        registry.record(deviceB)

        XCTAssertEqual(Set(registry.allDeviceIdHashes()), Set([deviceA, deviceB]))
    }

    func testOnIdentityRecordedFiresOnlyForNewDeviceIdHashes() {
        let registry = VerifiedIdentityRegistry()
        let hash = Data([1, 2, 3, 4, 5, 6])
        var recorded: [Data] = []
        registry.onIdentityRecorded = { recorded.append($0) }

        registry.record(hash)
        registry.record(hash) // duplicado, no debe volver a disparar el callback

        XCTAssertEqual(recorded, [hash])
    }
}
