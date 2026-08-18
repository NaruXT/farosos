import CryptoKit
import PacketCodec
import XCTest
@testable import BeaconRadio

final class SignatureFragmentAssemblerTests: XCTestCase {
    private final class ManualClock {
        var current = Date(timeIntervalSince1970: 0)
        func advance(by seconds: TimeInterval) { current.addTimeInterval(seconds) }
    }

    private func makeIdentityFragments(deviceIdHash: Data = Data([1, 2, 3, 4, 5, 6]), ttl: UInt8 = 16) -> [FragmentoFirmaPacket] {
        let privateKey = Curve25519.Signing.PrivateKey()
        let publicKey = privateKey.publicKey.rawRepresentation
        let signature = CaseASignature.sign(privateKey: privateKey)
        return SignatureFragmenter.fragment(publicKey: publicKey, signature: signature, deviceIdHash: deviceIdHash, ttl: ttl)
    }

    func testIsNotVerifiedBeforeAllFragmentsArrive() {
        let assembler = SignatureFragmentAssembler()
        let fragments = makeIdentityFragments()

        for fragment in fragments.dropLast() {
            assembler.receive(fragment)
        }

        XCTAssertFalse(assembler.isVerified(fragments[0].deviceIdHash))
    }

    func testBecomesVerifiedOnceAllFragmentsArriveInOrder() {
        let assembler = SignatureFragmentAssembler()
        let deviceIdHash = Data([9, 9, 9, 9, 9, 9])
        let fragments = makeIdentityFragments(deviceIdHash: deviceIdHash)

        var reportedPublicKey: Data?
        var reportedDeviceIdHash: Data?
        assembler.onIdentityVerified = { hash, publicKey in
            reportedDeviceIdHash = hash
            reportedPublicKey = publicKey
        }

        var completed = false
        for fragment in fragments {
            completed = assembler.receive(fragment) || completed
        }

        XCTAssertTrue(completed)
        XCTAssertTrue(assembler.isVerified(deviceIdHash))
        XCTAssertEqual(reportedDeviceIdHash, deviceIdHash)
        XCTAssertEqual(reportedPublicKey?.count, 32)
    }

    func testBecomesVerifiedRegardlessOfFragmentArrivalOrder() {
        let assembler = SignatureFragmentAssembler()
        let fragments = makeIdentityFragments().shuffled()

        for fragment in fragments {
            assembler.receive(fragment)
        }

        XCTAssertTrue(assembler.isVerified(fragments[0].deviceIdHash))
    }

    func testOnIdentityVerifiedFiresOnlyOnce() {
        let assembler = SignatureFragmentAssembler()
        let fragments = makeIdentityFragments()

        var callCount = 0
        assembler.onIdentityVerified = { _, _ in callCount += 1 }

        for fragment in fragments { assembler.receive(fragment) }
        // Retransmisión completa del mismo conjunto (p. ej. otro relay lo
        // reenvía de nuevo) no debe volver a disparar el callback.
        for fragment in fragments { assembler.receive(fragment) }

        XCTAssertEqual(callCount, 1)
    }

    func testTamperedFragmentNeverVerifiesEvenWithAllIndicesPresent() {
        let assembler = SignatureFragmentAssembler()
        var fragments = makeIdentityFragments()
        var tampered = fragments[3]
        tampered.chunk[0] ^= 0xFF
        fragments[3] = tampered

        for fragment in fragments { assembler.receive(fragment) }

        XCTAssertFalse(assembler.isVerified(fragments[0].deviceIdHash))
    }

    func testTwoDifferentDevicesAreTrackedIndependently() {
        let assembler = SignatureFragmentAssembler()
        let deviceA = Data([1, 1, 1, 1, 1, 1])
        let deviceB = Data([2, 2, 2, 2, 2, 2])
        let fragmentsA = makeIdentityFragments(deviceIdHash: deviceA)
        let fragmentsB = makeIdentityFragments(deviceIdHash: deviceB)

        for fragment in fragmentsA.dropLast() { assembler.receive(fragment) }
        for fragment in fragmentsB { assembler.receive(fragment) }

        XCTAssertFalse(assembler.isVerified(deviceA), "a A todavía le falta un fragmento")
        XCTAssertTrue(assembler.isVerified(deviceB))
    }

    // MARK: - Memoria acotada (capacidad + TTL), mismo motivo que `DedupCache`

    func testExceedingCapacityEvictsTheOldestIncompleteDevice() {
        let assembler = SignatureFragmentAssembler(capacity: 2)
        let deviceA = Data([1, 1, 1, 1, 1, 1])
        let deviceB = Data([2, 2, 2, 2, 2, 2])
        let deviceC = Data([3, 3, 3, 3, 3, 3])
        let fragmentsA = makeIdentityFragments(deviceIdHash: deviceA)
        let fragmentsB = makeIdentityFragments(deviceIdHash: deviceB)
        let fragmentsC = makeIdentityFragments(deviceIdHash: deviceC)

        assembler.receive(fragmentsA[0])
        assembler.receive(fragmentsB[0])
        assembler.receive(fragmentsC[0]) // sobre capacidad (2) -> desaloja el progreso parcial de A

        for fragment in fragmentsA.dropFirst() {
            assembler.receive(fragment)
        }
        XCTAssertFalse(assembler.isVerified(deviceA), "el progreso parcial de A se desalojó, el índice 0 se perdió")

        for fragment in fragmentsA {
            assembler.receive(fragment)
        }
        XCTAssertTrue(assembler.isVerified(deviceA), "reenviar el conjunto completo debe volver a acumularse desde cero")
    }

    func testIncompleteFragmentsExpireAfterTtl() {
        let clock = ManualClock()
        let assembler = SignatureFragmentAssembler(ttl: 30 * 60, now: { clock.current })
        let fragments = makeIdentityFragments()

        assembler.receive(fragments[0])
        clock.advance(by: 30 * 60)

        for fragment in fragments.dropFirst() {
            assembler.receive(fragment)
        }
        XCTAssertFalse(assembler.isVerified(fragments[0].deviceIdHash), "el fragmento 0 expiró, el conjunto sigue incompleto")
    }
}
