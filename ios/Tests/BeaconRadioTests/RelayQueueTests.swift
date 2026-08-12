import XCTest
import PacketCodec
import TestSupport
@testable import BeaconRadio

final class RelayQueueTests: XCTestCase {
    private func packet(deviceByte: UInt8, nonce: UInt16, ttl: UInt8 = 10, status: BeaconPacket.Status = .ok) -> BeaconPacket {
        BeaconPacket(
            messageType: .beacon,
            deviceIdHash: Data([deviceByte, 0, 0, 0, 0, 0]),
            status: status,
            latitudeE7: 0,
            longitudeE7: 0,
            timestamp: 0,
            ttl: ttl,
            nonce: nonce,
            sequence: 1
        )
    }

    private func gatewayAnnouncement(deviceByte: UInt8, nonce: UInt16) -> BeaconPacket {
        BeaconPacket(
            messageType: .gatewayAnnounce,
            deviceIdHash: Data([deviceByte, 0, 0, 0, 0, 0]),
            status: .gatewayDisponible,
            latitudeE7: 0,
            longitudeE7: 0,
            timestamp: 0,
            ttl: 10,
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

    // MARK: - Slot de gateway (ticket #15)

    func testGatewayAnnouncementOccupiesFixedSlotAndParticipatesInRotation() {
        let scheduler = FakeScheduler()
        let queue = RelayQueue(scheduler: scheduler, window: 1)
        var observed: [BeaconPacket] = []
        queue.onCurrentPacketChanged = { observed.append($0) }
        let own = packet(deviceByte: 1, nonce: 1)
        let gateway = gatewayAnnouncement(deviceByte: 9, nonce: 1)

        queue.updateOwnBeacon(own)
        queue.updateGatewayAnnouncement(gateway)
        queue.start()
        scheduler.advance(by: 1)

        XCTAssertEqual(observed, [own, gateway])
    }

    func testGatewayAnnouncementIsReplacedInPlaceNotDuplicated() {
        let scheduler = FakeScheduler()
        let queue = RelayQueue(scheduler: scheduler, window: 1)
        var observed: [BeaconPacket] = []
        queue.onCurrentPacketChanged = { observed.append($0) }
        let own = packet(deviceByte: 1, nonce: 1)
        let firstAnnouncement = gatewayAnnouncement(deviceByte: 9, nonce: 1)
        let updatedAnnouncement = gatewayAnnouncement(deviceByte: 9, nonce: 2)

        queue.updateOwnBeacon(own)
        queue.updateGatewayAnnouncement(firstAnnouncement)
        queue.updateGatewayAnnouncement(updatedAnnouncement)
        queue.start()
        scheduler.advance(by: 1)
        scheduler.advance(by: 1)

        XCTAssertEqual(observed, [own, updatedAnnouncement, own], "un solo slot de gateway, reemplazado en su lugar")
    }

    func testGatewayAnnouncementNeverEvicted() {
        let scheduler = FakeScheduler()
        let queue = RelayQueue(scheduler: scheduler, window: 1, foreignCapacity: 1)
        var observed: [BeaconPacket] = []
        queue.onCurrentPacketChanged = { observed.append($0) }
        let own = packet(deviceByte: 1, nonce: 1)
        let gateway = gatewayAnnouncement(deviceByte: 9, nonce: 1)
        let foreignA = packet(deviceByte: 2, nonce: 1)
        let foreignB = packet(deviceByte: 3, nonce: 1) // desaloja foreignA (tope=1), nunca al gateway

        queue.updateOwnBeacon(own)
        queue.updateGatewayAnnouncement(gateway)
        queue.enqueueForeignBeacon(foreignA)
        queue.enqueueForeignBeacon(foreignB)
        queue.start()
        scheduler.advance(by: 1)
        scheduler.advance(by: 1)

        XCTAssertEqual(observed, [own, gateway, foreignB])
    }

    func testClearGatewayAnnouncementRemovesItFromRotation() {
        let scheduler = FakeScheduler()
        let queue = RelayQueue(scheduler: scheduler, window: 1)
        var observed: [BeaconPacket] = []
        queue.onCurrentPacketChanged = { observed.append($0) }
        let own = packet(deviceByte: 1, nonce: 1)
        let gateway = gatewayAnnouncement(deviceByte: 9, nonce: 1)

        queue.updateOwnBeacon(own)
        queue.updateGatewayAnnouncement(gateway)
        queue.clearGatewayAnnouncement()
        queue.start()
        scheduler.advance(by: 1)

        XCTAssertEqual(observed, [own, own], "sin slot de gateway, la rotación solo tiene al propio beacon")
    }

    // MARK: - Prioridad de descarte bajo BAJO_CONSUMO (ticket #15)

    func testCapacityStaysLruWhenNotLowPower() {
        let scheduler = FakeScheduler()
        let queue = RelayQueue(scheduler: scheduler, window: 1, foreignCapacity: 2)
        var observed: [BeaconPacket] = []
        queue.onCurrentPacketChanged = { observed.append($0) }
        let own = packet(deviceByte: 1, nonce: 1)
        let foreignA = packet(deviceByte: 2, nonce: 1, status: .ayuda)
        let foreignB = packet(deviceByte: 3, nonce: 1, status: .ok)
        let foreignC = packet(deviceByte: 4, nonce: 1, status: .ok)

        queue.updateOwnBeacon(own)
        queue.enqueueForeignBeacon(foreignA)
        queue.enqueueForeignBeacon(foreignB)
        queue.enqueueForeignBeacon(foreignC) // isLowPower=false (default): LRU puro, desaloja foreignA aunque sea AYUDA

        queue.start()
        scheduler.advance(by: 1)
        scheduler.advance(by: 1)
        scheduler.advance(by: 1)

        XCTAssertEqual(observed, [own, foreignB, foreignC, own], "fuera de bajo consumo, la prioridad no aplica")
    }

    func testLowPowerEvictsOkEntryFirstEvenIfOlderEntriesArePending() {
        let scheduler = FakeScheduler()
        let queue = RelayQueue(scheduler: scheduler, window: 1, foreignCapacity: 2)
        var observed: [BeaconPacket] = []
        queue.onCurrentPacketChanged = { observed.append($0) }
        let own = packet(deviceByte: 1, nonce: 1)
        let foreignA = packet(deviceByte: 2, nonce: 1, status: .ayuda) // más antiguo, protegido
        let foreignB = packet(deviceByte: 3, nonce: 1, status: .ok) // más nuevo, pero OK
        let foreignC = packet(deviceByte: 4, nonce: 1, status: .silencioTimeout)

        queue.isLowPower = true
        queue.updateOwnBeacon(own)
        queue.enqueueForeignBeacon(foreignA)
        queue.enqueueForeignBeacon(foreignB)
        queue.enqueueForeignBeacon(foreignC) // tope=2: debe desalojar foreignB (OK), no foreignA (AYUDA)

        queue.start()
        scheduler.advance(by: 1)
        scheduler.advance(by: 1)
        scheduler.advance(by: 1)

        XCTAssertEqual(observed, [own, foreignA, foreignC, own], "el OK se descarta antes que el AYUDA más antiguo")
    }

    func testLowPowerNeverEvictsProtectedStatusWhenNoOkAvailable() {
        let scheduler = FakeScheduler()
        let queue = RelayQueue(scheduler: scheduler, window: 1, foreignCapacity: 2)
        var observed: [BeaconPacket] = []
        queue.onCurrentPacketChanged = { observed.append($0) }
        let own = packet(deviceByte: 1, nonce: 1)
        let foreignA = packet(deviceByte: 2, nonce: 1, status: .ayuda)
        let foreignB = packet(deviceByte: 3, nonce: 1, status: .silencioTimeout)
        let foreignC = packet(deviceByte: 4, nonce: 1, status: .sinConfirmar) // sin ningún OK disponible

        queue.isLowPower = true
        queue.updateOwnBeacon(own)
        queue.enqueueForeignBeacon(foreignA)
        queue.enqueueForeignBeacon(foreignB)
        queue.enqueueForeignBeacon(foreignC) // no debe desalojar nada — la cola crece por encima del tope

        queue.start()
        scheduler.advance(by: 1)
        scheduler.advance(by: 1)
        scheduler.advance(by: 1)
        scheduler.advance(by: 1)

        XCTAssertEqual(
            observed, [own, foreignA, foreignB, foreignC, own],
            "sin ningún OK que sacrificar, ninguna entrada protegida se pierde"
        )
    }

    func testLowPowerEvictsOldestOkAmongMultipleOkEntries() {
        let scheduler = FakeScheduler()
        let queue = RelayQueue(scheduler: scheduler, window: 1, foreignCapacity: 2)
        var observed: [BeaconPacket] = []
        queue.onCurrentPacketChanged = { observed.append($0) }
        let own = packet(deviceByte: 1, nonce: 1)
        let foreignA = packet(deviceByte: 2, nonce: 1, status: .ok) // OK más antiguo
        let foreignB = packet(deviceByte: 3, nonce: 1, status: .ok)
        let foreignC = packet(deviceByte: 4, nonce: 1, status: .ayuda)

        queue.isLowPower = true
        queue.updateOwnBeacon(own)
        queue.enqueueForeignBeacon(foreignA)
        queue.enqueueForeignBeacon(foreignB)
        queue.enqueueForeignBeacon(foreignC) // tope=2: desaloja el OK más antiguo (foreignA), no foreignB

        queue.start()
        scheduler.advance(by: 1)
        scheduler.advance(by: 1)
        scheduler.advance(by: 1)

        XCTAssertEqual(observed, [own, foreignB, foreignC, own])
    }
}
