import CryptoKit
import XCTest
@testable import DeviceIdentity

/// `difficultyBits` bajo en la mayoría de los tests (no el default de
/// producción, 20) para mantener el suite rápido — `solve` es fuerza bruta,
/// el costo real solo importa en la ejecución de la app, no acá.
final class ProofOfWorkTests: XCTestCase {
    private let deviceIdHash = Data([0x01, 0x02, 0x03, 0x04, 0x05, 0x06])

    private func repoRootURL() -> URL {
        // #filePath = .../ios/Tests/DeviceIdentityTests/ProofOfWorkTests.swift
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent() // quita el archivo -> DeviceIdentityTests/
            .deletingLastPathComponent() // -> Tests/
            .deletingLastPathComponent() // -> ios/
            .deletingLastPathComponent() // -> raíz del repo
    }

    private func loadVectorsJSON() throws -> [String: Any] {
        let vectorsURL = repoRootURL()
            .appendingPathComponent("spec")
            .appendingPathComponent("test-vectors.json")
        let data = try Data(contentsOf: vectorsURL)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func hexToData(_ hex: String) -> Data {
        var data = Data(capacity: hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            data.append(UInt8(hex[index..<next], radix: 16)!)
            index = next
        }
        return data
    }

    /// Vectores compartidos con Android (`spec/test-vectors.json`,
    /// `pow_vectors`) — #51 exige que un sello calculado en una plataforma se
    /// verifique correctamente en la otra. Mismo principio que
    /// `DeviceIdentityHashVectorTests`/`VectorLoadingTests`.
    func testIsValidAcceptsEveryPowVector() throws {
        let json = try loadVectorsJSON()
        let vectors = try XCTUnwrap(json["pow_vectors"] as? [[String: Any]])
        XCTAssertFalse(vectors.isEmpty)

        for vector in vectors {
            let name = try XCTUnwrap(vector["name"] as? String)
            let hash = hexToData(try XCTUnwrap(vector["device_id_hash_hex"] as? String))
            let difficultyBits = try XCTUnwrap(vector["difficulty_bits"] as? Int)
            let nonce = hexToData(try XCTUnwrap(vector["nonce_hex"] as? String))
            let expectedDigest = hexToData(try XCTUnwrap(vector["expected_digest_hex"] as? String))

            XCTAssertTrue(ProofOfWork.isValid(deviceIdHash: hash, nonce: nonce, difficultyBits: difficultyBits), name)
            XCTAssertEqual(SHA256.hash(data: hash + nonce).map { $0 }, Array(expectedDigest), name)
        }
    }

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
