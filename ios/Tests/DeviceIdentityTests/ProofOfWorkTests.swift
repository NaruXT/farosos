import XCTest
@testable import DeviceIdentity

/// `difficultyBits` bajo en la mayoría de los tests (no el default de
/// producción, 20) para mantener el suite rápido — `solve` es fuerza bruta,
/// el costo real solo importa en la ejecución de la app, no acá.
final class ProofOfWorkTests: XCTestCase {
    private let deviceIdHash = Data([0x01, 0x02, 0x03, 0x04, 0x05, 0x06])

    func testLeadingZeroBitsAllZeroBytes() {
        XCTAssertEqual(ProofOfWork.leadingZeroBits(of: Data([0x00, 0x00])), 16)
    }

    func testLeadingZeroBitsFirstByteNonZero() {
        XCTAssertEqual(ProofOfWork.leadingZeroBits(of: Data([0b0010_0000])), 2)
    }

    func testLeadingZeroBitsSkipsLeadingZeroBytes() {
        XCTAssertEqual(ProofOfWork.leadingZeroBits(of: Data([0x00, 0b0000_0001])), 15)
    }

    func testSolveProducesASealThatIsValid() {
        let nonce = ProofOfWork.solve(deviceIdHash: deviceIdHash, difficultyBits: 8)
        XCTAssertTrue(ProofOfWork.isValid(deviceIdHash: deviceIdHash, nonce: nonce, difficultyBits: 8))
    }

    func testSolveIsDeterministicForTheSameInput() {
        let first = ProofOfWork.solve(deviceIdHash: deviceIdHash, difficultyBits: 8)
        let second = ProofOfWork.solve(deviceIdHash: deviceIdHash, difficultyBits: 8)
        XCTAssertEqual(first, second)
    }

    func testIsValidRejectsATamperedNonce() {
        let nonce = ProofOfWork.solve(deviceIdHash: deviceIdHash, difficultyBits: 8)
        var tampered = nonce
        tampered[tampered.count - 1] ^= 0xFF
        XCTAssertFalse(ProofOfWork.isValid(deviceIdHash: deviceIdHash, nonce: tampered, difficultyBits: 8))
    }

    func testIsValidRejectsASealComputedForADifferentDeviceIdHash() {
        let otherHash = Data([0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F])
        let nonce = ProofOfWork.solve(deviceIdHash: deviceIdHash, difficultyBits: 8)
        XCTAssertFalse(ProofOfWork.isValid(deviceIdHash: otherHash, nonce: nonce, difficultyBits: 8))
    }

    func testDefaultDifficultyIsTwentyBits() {
        XCTAssertEqual(ProofOfWork.difficultyBits, 20)
    }

    func testSolveAtDefaultDifficultyProducesAValidSeal() {
        let nonce = ProofOfWork.solve(deviceIdHash: deviceIdHash)
        XCTAssertTrue(ProofOfWork.isValid(deviceIdHash: deviceIdHash, nonce: nonce))
    }
}
