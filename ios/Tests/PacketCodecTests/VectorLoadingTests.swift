import XCTest
@testable import PacketCodec

/// Valida solo que el plumbing hacia `spec/test-vectors.json` funciona. Los
/// tests reales de encode/decode contra estos vectores se agregan junto con
/// la implementación de `BeaconPacketCodec` (ver issue de Fase 1) — no antes,
/// para no fingir cobertura de un codec que todavía no existe.
final class VectorLoadingTests: XCTestCase {
    private func repoRootURL() -> URL {
        // ios/Tests/PacketCodecTests/VectorLoadingTests.swift -> raíz del repo
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent() // PacketCodecTests/
            .deletingLastPathComponent() // Tests/
            .deletingLastPathComponent() // ios/
    }

    func testSharedVectorsFileIsReadableAndNonEmpty() throws {
        let vectorsURL = repoRootURL()
            .appendingPathComponent("spec")
            .appendingPathComponent("test-vectors.json")

        let data = try Data(contentsOf: vectorsURL)
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let vectors = try XCTUnwrap(json?["vectors"] as? [[String: Any]])

        XCTAssertEqual(json?["byte_order"] as? String, "little-endian")
        XCTAssertEqual(json?["packet_size_bytes"] as? Int, BeaconPacket.packetSize)
        XCTAssertFalse(vectors.isEmpty)
    }
}
