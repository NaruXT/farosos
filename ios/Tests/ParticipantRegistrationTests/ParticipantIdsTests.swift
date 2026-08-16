import XCTest
@testable import ParticipantRegistration

final class ParticipantIdsTests: XCTestCase {
    func testDeviceIdHashHexEncodesBytesAsLowercaseHex() {
        let hash = Data([0xAB, 0x01, 0xFF])

        XCTAssertEqual(ParticipantIds.deviceIdHashHex(hash), "ab01ff")
    }

    func testParticipantDocIdMatchesDeviceIdHashHex() {
        let hash = Data([0x12, 0x34, 0x56, 0x78, 0x9a, 0xbc])

        XCTAssertEqual(ParticipantIds.participantDocId(deviceIdHash: hash), ParticipantIds.deviceIdHashHex(hash))
    }
}
