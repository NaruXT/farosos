import XCTest
@testable import BeaconRadio

final class DedupCacheTests: XCTestCase {
    private final class ManualClock {
        var current = Date(timeIntervalSince1970: 0)
        func advance(by seconds: TimeInterval) { current.addTimeInterval(seconds) }
    }

    private func makeKey(_ deviceByte: UInt8 = 1, nonce: UInt16 = 42) -> DedupCache.Key {
        DedupCache.Key(deviceIdHash: Data([deviceByte, 0, 0, 0, 0, 0]), nonce: nonce)
    }

    private func makeMacKey(_ deviceByte: UInt8 = 1, mac: [UInt8] = [1, 2, 3, 4]) -> DedupCache.Key {
        DedupCache.Key(deviceIdHash: Data([deviceByte, 0, 0, 0, 0, 0]), mac: Data(mac))
    }

    func testFirstInsertionIsAccepted() {
        let cache = DedupCache()
        XCTAssertTrue(cache.insertIfAbsent(makeKey()))
    }

    func testSecondInsertionOfSameKeyIsRejectedAsDuplicate() {
        let cache = DedupCache()
        let key = makeKey()

        XCTAssertTrue(cache.insertIfAbsent(key))
        XCTAssertFalse(cache.insertIfAbsent(key))
    }

    func testDifferentNonceForSameDeviceIsNotADuplicate() {
        let cache = DedupCache()
        XCTAssertTrue(cache.insertIfAbsent(makeKey(1, nonce: 1)))
        XCTAssertTrue(cache.insertIfAbsent(makeKey(1, nonce: 2)))
    }

    func testEntryExpiresAfterTtl() {
        let clock = ManualClock()
        let cache = DedupCache(ttl: 30 * 60, now: { clock.current })
        let key = makeKey()

        XCTAssertTrue(cache.insertIfAbsent(key))
        clock.advance(by: 30 * 60)

        XCTAssertTrue(cache.insertIfAbsent(key), "una entrada expirada debe tratarse como nueva")
    }

    func testEntryIsStillADuplicateJustBeforeTtlExpires() {
        let clock = ManualClock()
        let cache = DedupCache(ttl: 30 * 60, now: { clock.current })
        let key = makeKey()

        XCTAssertTrue(cache.insertIfAbsent(key))
        clock.advance(by: 30 * 60 - 1)

        XCTAssertFalse(cache.insertIfAbsent(key))
    }

    func testLruEvictsOldestEntryWhenOverCapacity() {
        let cache = DedupCache(capacity: 2)
        let keyA = makeKey(1)
        let keyB = makeKey(2)
        let keyC = makeKey(3)

        XCTAssertTrue(cache.insertIfAbsent(keyA))
        XCTAssertTrue(cache.insertIfAbsent(keyB))
        XCTAssertTrue(cache.insertIfAbsent(keyC)) // evicts keyA (la menos usada recientemente)

        XCTAssertTrue(cache.insertIfAbsent(keyA), "keyA fue desalojada, debe tratarse como nueva")
        XCTAssertFalse(cache.insertIfAbsent(keyC), "keyC sigue vigente")
    }

    func testTouchingAnEntryProtectsItFromEviction() {
        let cache = DedupCache(capacity: 2)
        let keyA = makeKey(1)
        let keyB = makeKey(2)
        let keyC = makeKey(3)

        XCTAssertTrue(cache.insertIfAbsent(keyA))
        XCTAssertTrue(cache.insertIfAbsent(keyB))
        XCTAssertFalse(cache.insertIfAbsent(keyA)) // touch: keyA vuelve a ser la más reciente
        XCTAssertTrue(cache.insertIfAbsent(keyC)) // debe desalojar keyB, no keyA

        XCTAssertFalse(cache.insertIfAbsent(keyA), "keyA fue tocada recientemente, no debió desalojarse")
        XCTAssertTrue(cache.insertIfAbsent(keyB), "keyB fue desalojada")
    }

    // MARK: - Caso B (`Versión=0x02`, clave `DeviceIdHash + MAC`, #39/#42)

    func testSameMacForSameDeviceIsADuplicate() {
        let cache = DedupCache()
        let key = makeMacKey()

        XCTAssertTrue(cache.insertIfAbsent(key))
        XCTAssertFalse(cache.insertIfAbsent(key), "el mismo beacon Caso B rebotado por varios relays debe verse como duplicado")
    }

    func testDifferentMacForSameDeviceIsNotADuplicate() {
        let cache = DedupCache()
        XCTAssertTrue(cache.insertIfAbsent(makeMacKey(1, mac: [1, 2, 3, 4])))
        XCTAssertTrue(
            cache.insertIfAbsent(makeMacKey(1, mac: [1, 2, 3, 5])),
            "un beacon con contenido distinto (Timestamp avanzado) cambia el MAC y debe verse como nuevo"
        )
    }

    func testMacKeyAndNonceKeyWithSameDeviceNeverCollide() {
        let cache = DedupCache()
        // Nonce=0x0201 (LE: 01 02) vs MAC=[1,2,3,4] — ambas empiezan igual en
        // los primeros 2 bytes, pero no deben confundirse entre sí.
        XCTAssertTrue(cache.insertIfAbsent(DedupCache.Key(deviceIdHash: Data([1, 0, 0, 0, 0, 0]), nonce: 0x0201)))
        XCTAssertTrue(cache.insertIfAbsent(makeMacKey(1, mac: [1, 2, 3, 4])))
    }

    private func makeFragHeaderKey(_ deviceByte: UInt8 = 1, fragHeader: UInt8) -> DedupCache.Key {
        DedupCache.Key(deviceIdHash: Data([deviceByte, 0, 0, 0, 0, 0]), fragHeader: fragHeader)
    }

    func testSameFragmentRetransmittedIsADuplicate() {
        let cache = DedupCache()
        let key = makeFragHeaderKey(1, fragHeader: 0x07) // índice=0, conteo=7
        XCTAssertTrue(cache.insertIfAbsent(key))
        XCTAssertFalse(cache.insertIfAbsent(key), "el mismo fragmento rebotado por varios relays debe verse como duplicado")
    }

    func testDifferentFragmentIndexOfSameDeviceIsNotADuplicate() {
        let cache = DedupCache()
        XCTAssertTrue(cache.insertIfAbsent(makeFragHeaderKey(1, fragHeader: 0x07))) // índice=0
        XCTAssertTrue(
            cache.insertIfAbsent(makeFragHeaderKey(1, fragHeader: 0x17)), // índice=1
            "los 7 fragmentos de una misma identidad deben convivir en el cache, no deduplicarse entre sí"
        )
    }

    func testFragHeaderKeyNeverCollidesWithNonceOrMacKeyOfSameDevice() {
        let cache = DedupCache()
        XCTAssertTrue(cache.insertIfAbsent(DedupCache.Key(deviceIdHash: Data([1, 0, 0, 0, 0, 0]), nonce: 0x0007)))
        XCTAssertTrue(cache.insertIfAbsent(makeMacKey(1, mac: [0, 0, 0, 7])))
        XCTAssertTrue(cache.insertIfAbsent(makeFragHeaderKey(1, fragHeader: 0x07)))
    }
}
