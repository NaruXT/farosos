import XCTest
import PacketCodec
@testable import BeaconRadio

final class RelayPolicyTests: XCTestCase {
    private func packet(ttl: UInt8) -> BeaconPacket {
        BeaconPacket(
            messageType: .beacon,
            deviceIdHash: Data([1, 2, 3, 4, 5, 6]),
            status: .ok,
            latitudeE7: 0,
            longitudeE7: 0,
            timestamp: 0,
            ttl: ttl,
            nonce: 42,
            sequence: 1
        )
    }

    func testTtlZeroIsNeverRelayed() {
        XCTAssertNil(RelayPolicy.decrementedForRelay(packet(ttl: 0)))
    }

    func testTtlDecrementsByOneWhenRelayed() {
        let relayed = RelayPolicy.decrementedForRelay(packet(ttl: 5))
        XCTAssertEqual(relayed?.ttl, 4)
    }

    func testTtlOneStillGetsRelayedOnceWithZeroResultingTtl() {
        let relayed = RelayPolicy.decrementedForRelay(packet(ttl: 1))
        XCTAssertEqual(relayed?.ttl, 0)
    }

    func testRelayedPacketPreservesOtherFields() {
        let original = packet(ttl: 10)
        let relayed = RelayPolicy.decrementedForRelay(original)
        XCTAssertEqual(relayed?.deviceIdHash, original.deviceIdHash)
        XCTAssertEqual(relayed?.nonce, original.nonce)
        XCTAssertEqual(relayed?.sequence, original.sequence)
        XCTAssertEqual(relayed?.status, original.status)
    }
}
