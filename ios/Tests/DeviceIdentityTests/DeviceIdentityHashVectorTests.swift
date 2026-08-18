import XCTest
@testable import DeviceIdentity

/// Compara `DeviceIdentityHash.fromPublicKey` contra `device_id_hash_vectors`
/// de `spec/test-vectors.json` (#39) — mismo principio que
/// `PacketCodecTests/VectorLoadingTests.swift`: la fuente de verdad vive en
/// un archivo compartido con Android, generado independientemente del codec
/// de ninguna plataforma.
final class DeviceIdentityHashVectorTests: XCTestCase {
    private func repoRootURL() -> URL {
        // #filePath = .../ios/Tests/DeviceIdentityTests/DeviceIdentityHashVectorTests.swift
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

    func testDeviceIdHashMatchesEveryVector() throws {
        let json = try loadVectorsJSON()
        let vectors = try XCTUnwrap(json["device_id_hash_vectors"] as? [[String: Any]])
        XCTAssertFalse(vectors.isEmpty)

        for vector in vectors {
            let name = try XCTUnwrap(vector["name"] as? String)
            let publicKey = hexToData(try XCTUnwrap(vector["public_key_ed25519_hex"] as? String))
            let expected = hexToData(try XCTUnwrap(vector["device_id_hash"] as? String))

            XCTAssertEqual(DeviceIdentityHash.fromPublicKey(publicKey), expected, name)
        }
    }

    func testDeviceIdHashIsSixBytes() throws {
        let json = try loadVectorsJSON()
        let vectors = try XCTUnwrap(json["device_id_hash_vectors"] as? [[String: Any]])
        let publicKey = hexToData(try XCTUnwrap(vectors[0]["public_key_ed25519_hex"] as? String))

        XCTAssertEqual(DeviceIdentityHash.fromPublicKey(publicKey).count, 6)
    }
}
