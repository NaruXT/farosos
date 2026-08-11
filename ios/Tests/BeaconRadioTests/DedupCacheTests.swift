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
}
