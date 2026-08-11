import XCTest
import PacketCodec
import TestSupport
@testable import BeaconRadio

final class RelayQueueTests: XCTestCase {
    private func packet(deviceByte: UInt8, nonce: UInt16, ttl: UInt8 = 10) -> BeaconPacket {
        BeaconPacket(
            messageType: .beacon,
            deviceIdHash: Data([deviceByte, 0, 0, 0, 0, 0]),
            status: .ok,
            latitudeE7: 0,
            longitudeE7: 0,
            timestamp: 0,
            ttl: ttl,
            nonce: nonce,
            sequence: 1
        )
    }

    func testStartImmediatelyExposesOwnBeaconWhenItsTheOnlyEntry() {
        let scheduler = FakeScheduler()
        let queue = RelayQueue(scheduler: scheduler, window: 1)
        var observed: [BeaconPacket] = []
        queue.onCurrentPacketChanged = { observed.append($0) }
        let own = packet(deviceByte: 1, nonce: 1)

        queue.updateOwnBeacon(own)
        queue.start()

        XCTAssertEqual(observed, [own])
    }

    func testRotatesBetweenOwnAndOneForeignBeacon() {
        let scheduler = FakeScheduler()
        let queue = RelayQueue(scheduler: scheduler, window: 1)
        var observed: [BeaconPacket] = []
        queue.onCurrentPacketChanged = { observed.append($0) }
        let own = packet(deviceByte: 1, nonce: 1)
        let foreign = packet(deviceByte: 2, nonce: 1)

        queue.updateOwnBeacon(own)
        queue.enqueueForeignBeacon(foreign)
        queue.start()
        scheduler.advance(by: 1)
        scheduler.advance(by: 1)

        XCTAssertEqual(observed, [own, foreign, own])
    }

    func testRotatesThroughMultipleForeignBeaconsInOrder() {
        let scheduler = FakeScheduler()
        let queue = RelayQueue(scheduler: scheduler, window: 1)
        var observed: [BeaconPacket] = []
        queue.onCurrentPacketChanged = { observed.append($0) }
        let own = packet(deviceByte: 1, nonce: 1)
        let foreignA = packet(deviceByte: 2, nonce: 1)
        let foreignB = packet(deviceByte: 3, nonce: 1)

        queue.updateOwnBeacon(own)
        queue.enqueueForeignBeacon(foreignA)
        queue.enqueueForeignBeacon(foreignB)
        queue.start()
        scheduler.advance(by: 1)
        scheduler.advance(by: 1)
        scheduler.advance(by: 1)

        XCTAssertEqual(observed, [own, foreignA, foreignB, own])
    }

    func testCapacityEvictsOldestForeignEntryOnOverflow() {
        let scheduler = FakeScheduler()
        let queue = RelayQueue(scheduler: scheduler, window: 1, foreignCapacity: 2)
        var observed: [BeaconPacket] = []
        queue.onCurrentPacketChanged = { observed.append($0) }
        let own = packet(deviceByte: 1, nonce: 1)
        let foreign1 = packet(deviceByte: 2, nonce: 1)
        let foreign2 = packet(deviceByte: 3, nonce: 1)
        let foreign3 = packet(deviceByte: 4, nonce: 1)

        queue.updateOwnBeacon(own)
        queue.enqueueForeignBeacon(foreign1)
        queue.enqueueForeignBeacon(foreign2)
        queue.enqueueForeignBeacon(foreign3) // debe desalojar foreign1

        queue.start()
        scheduler.advance(by: 1)
        scheduler.advance(by: 1)
        scheduler.advance(by: 1)

        XCTAssertEqual(observed, [own, foreign2, foreign3, own], "foreign1 fue desalojado, no debe aparecer en la rotación")
    }

    func testEvictingTheCurrentlyShownEntryDoesNotSkipTheNextOne() {
        let scheduler = FakeScheduler()
        let queue = RelayQueue(scheduler: scheduler, window: 1, foreignCapacity: 2)
        var observed: [BeaconPacket] = []
        queue.onCurrentPacketChanged = { observed.append($0) }
        let own = packet(deviceByte: 1, nonce: 1)
        let foreignA = packet(deviceByte: 2, nonce: 1)
        let foreignB = packet(deviceByte: 3, nonce: 1)
        let foreignC = packet(deviceByte: 4, nonce: 1)

        queue.updateOwnBeacon(own)
        queue.enqueueForeignBeacon(foreignA)
        queue.enqueueForeignBeacon(foreignB)
        queue.start() // muestra own
        scheduler.advance(by: 1) // muestra foreignA — esa es la entrada "actual"

        queue.enqueueForeignBeacon(foreignC) // tope=2, desaloja foreignA (justo la que se está mostrando)
        scheduler.advance(by: 1)

        XCTAssertEqual(
            observed, [own, foreignA, own],
            "al desalojarse la entrada mostrada, debe reanudar desde el principio en vez de saltarse foreignB silenciosamente"
        )
    }

    func testEnqueueingSameKeyReplacesContentWithoutGrowingQueue() {
        let scheduler = FakeScheduler()
        let queue = RelayQueue(scheduler: scheduler, window: 1, foreignCapacity: 5)
        var observed: [BeaconPacket] = []
        queue.onCurrentPacketChanged = { observed.append($0) }
        let own = packet(deviceByte: 1, nonce: 1)
        let firstSeen = packet(deviceByte: 2, nonce: 1, ttl: 10)
        let sameKeyAgain = packet(deviceByte: 2, nonce: 1, ttl: 3)

        queue.updateOwnBeacon(own)
        queue.enqueueForeignBeacon(firstSeen)
        queue.enqueueForeignBeacon(sameKeyAgain)
        queue.start()
        scheduler.advance(by: 1)
        scheduler.advance(by: 1)

        XCTAssertEqual(observed, [own, sameKeyAgain, own], "misma clave dos veces no debe duplicar la entrada")
    }

    func testStopCancelsFurtherRotation() {
        let scheduler = FakeScheduler()
        let queue = RelayQueue(scheduler: scheduler, window: 1)
        var observed: [BeaconPacket] = []
        queue.onCurrentPacketChanged = { observed.append($0) }
        let own = packet(deviceByte: 1, nonce: 1)
        let foreign = packet(deviceByte: 2, nonce: 1)

        queue.updateOwnBeacon(own)
        queue.enqueueForeignBeacon(foreign)
        queue.start()
        queue.stop()
        scheduler.advance(by: 10)

        XCTAssertEqual(observed, [own], "tras stop(), no debe seguir rotando")
    }
}
