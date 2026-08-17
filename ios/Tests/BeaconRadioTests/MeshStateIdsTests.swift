import XCTest
@testable import BeaconRadio

final class MeshStateIdsTests: XCTestCase {
    func testDeviceIdHashHexEncodesBytesAsLowercaseHex() {
        let hash = Data([0xAB, 0x01, 0xFF])

        XCTAssertEqual(MeshStateIds.deviceIdHashHex(hash), "ab01ff")
    }

    func testDocIdCombinesHashHexAndSequenceWithUnderscore() {
        let hash = Data([0x12, 0x34, 0x56, 0x78, 0x9a, 0xbc])

        XCTAssertEqual(MeshStateIds.docId(deviceIdHash: hash, sequence: 7), "123456789abc_7")
    }
}
