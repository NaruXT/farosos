import XCTest
import PacketCodec
@testable import BeaconRadio

final class LocalBeaconFactoryTests: XCTestCase {
    private struct FixedNonceGenerator: NonceGenerating {
        let value: UInt16
        func nextNonce() -> UInt16 { value }
    }

    func testMakeBeaconFillsFieldsFromInputs() {
        let deviceIdHash = Data([9, 8, 7, 6, 5, 4])
        let now = Date(timeIntervalSince1970: 1_700_000_000)

        let packet = LocalBeaconFactory.makeBeacon(
            deviceIdHash: deviceIdHash,
            status: .ayuda,
            sequence: 5,
            now: now,
            nonceGenerator: FixedNonceGenerator(value: 0x1234)
        )

        XCTAssertEqual(packet.messageType, .beacon)
        XCTAssertEqual(packet.deviceIdHash, deviceIdHash)
        XCTAssertEqual(packet.status, .ayuda)
        XCTAssertEqual(packet.sequence, 5)
        XCTAssertEqual(packet.timestamp, 1_700_000_000)
        XCTAssertEqual(packet.nonce, 0x1234)
        XCTAssertEqual(packet.ttl, LocalBeaconFactory.initialTtl)
    }

    func testMakeBeaconUsesFreshNoncePerCall() {
        let deviceIdHash = Data([9, 8, 7, 6, 5, 4])
        var nextValue: UInt16 = 1
        struct SequentialNonceGenerator: NonceGenerating {
            let next: () -> UInt16
            func nextNonce() -> UInt16 { next() }
        }
        let generator = SequentialNonceGenerator(next: { defer { nextValue += 1 }; return nextValue })

        let first = LocalBeaconFactory.makeBeacon(
            deviceIdHash: deviceIdHash, status: .ok, sequence: 1, now: Date(), nonceGenerator: generator
        )
        let second = LocalBeaconFactory.makeBeacon(
            deviceIdHash: deviceIdHash, status: .ok, sequence: 1, now: Date(), nonceGenerator: generator
        )

        XCTAssertNotEqual(first.nonce, second.nonce)
    }

    func testMakeGatewayAnnouncementFillsFieldsFromInputs() {
        let deviceIdHash = Data([9, 8, 7, 6, 5, 4])
        let now = Date(timeIntervalSince1970: 1_700_000_000)

        let packet = LocalBeaconFactory.makeGatewayAnnouncement(
            deviceIdHash: deviceIdHash,
            sequence: 5,
            now: now,
            nonceGenerator: FixedNonceGenerator(value: 0x1234)
        )

        XCTAssertEqual(packet.messageType, .gatewayAnnounce)
        XCTAssertEqual(packet.deviceIdHash, deviceIdHash)
        XCTAssertEqual(packet.status, .gatewayDisponible)
        XCTAssertEqual(packet.sequence, 5)
        XCTAssertEqual(packet.timestamp, 1_700_000_000)
        XCTAssertEqual(packet.nonce, 0x1234)
        XCTAssertEqual(packet.ttl, LocalBeaconFactory.initialTtl)
    }
}
