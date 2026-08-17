import XCTest
import PacketCodec
@testable import BeaconRadio

final class MeshStateRegistryTests: XCTestCase {
    private func makePacket(deviceIdHash: Data = Data([1, 2, 3, 4, 5, 6]), status: BeaconPacket.Status = .ok, sequence: UInt8) -> BeaconPacket {
        BeaconPacket(
            messageType: .beacon,
            deviceIdHash: deviceIdHash,
            status: status,
            latitudeE7: 10,
            longitudeE7: 20,
            timestamp: 1_700_000_000,
            ttl: 16,
            nonce: 0x1234,
            sequence: sequence
        )
    }

    func testUpdateStoresFirstStateSeenForADevice() {
        let registry = MeshStateRegistry()
        let packet = makePacket(sequence: 1)

        let accepted = registry.update(with: packet)

        XCTAssertTrue(accepted)
        XCTAssertEqual(registry.allStates(), [MeshParticipantState(packet: packet)])
    }

    func testUpdateAcceptsStrictlyNewerSequence() {
        let registry = MeshStateRegistry()
        registry.update(with: makePacket(sequence: 1))

        let accepted = registry.update(with: makePacket(status: .ayuda, sequence: 2))

        XCTAssertTrue(accepted)
        XCTAssertEqual(registry.allStates().first?.status, .ayuda)
        XCTAssertEqual(registry.allStates().first?.sequence, 2)
    }

    func testUpdateRejectsEqualOrOlderSequence() {
        let registry = MeshStateRegistry()
        registry.update(with: makePacket(status: .ayuda, sequence: 5))

        let acceptedEqual = registry.update(with: makePacket(status: .ok, sequence: 5))
        let acceptedOlder = registry.update(with: makePacket(status: .ok, sequence: 3))

        XCTAssertFalse(acceptedEqual)
        XCTAssertFalse(acceptedOlder)
        XCTAssertEqual(registry.allStates().first?.status, .ayuda) // sin cambios
    }

    func testUpdateTracksMultipleDevicesIndependently() {
        let registry = MeshStateRegistry()
        let deviceA = Data([1, 1, 1, 1, 1, 1])
        let deviceB = Data([2, 2, 2, 2, 2, 2])

        registry.update(with: makePacket(deviceIdHash: deviceA, sequence: 1))
        registry.update(with: makePacket(deviceIdHash: deviceB, sequence: 1))

        XCTAssertEqual(Set(registry.allStates().map(\.deviceIdHash)), Set([deviceA, deviceB]))
    }

    func testOnStateUpdatedFiresOnlyWhenAccepted() {
        let registry = MeshStateRegistry()
        var observed: [UInt8] = []
        registry.onStateUpdated = { observed.append($0.sequence) }

        registry.update(with: makePacket(sequence: 1))
        registry.update(with: makePacket(sequence: 1)) // rechazado, no dispara
        registry.update(with: makePacket(sequence: 2))

        XCTAssertEqual(observed, [1, 2])
    }
}

private extension MeshParticipantState {
    init(packet: BeaconPacket) {
        self.init(
            deviceIdHash: packet.deviceIdHash,
            status: packet.status,
            latitudeE7: packet.latitudeE7,
            longitudeE7: packet.longitudeE7,
            timestamp: packet.timestamp,
            sequence: packet.sequence
        )
    }
}
