import XCTest
@testable import PacketCodec

/// Compara `FragmentoFirmaPacketCodec` (Caso A, `Versión=0x01`, `Tipo=3`)
/// contra `spec/test-vectors.json`, clave `fragmento_firma` (#38/#44) —
/// mismo principio que `CaseBVectorLoadingTests.swift`: fuente de verdad
/// compartida con Android, generada independientemente del codec de
/// ninguna plataforma.
final class FragmentoFirmaVectorTests: XCTestCase {
    private func loadVectorsJSON() throws -> [String: Any] {
        try TestVectorFile.load()
    }

    private func hexToData(_ hex: String) -> Data {
        TestVectorFile.hexToData(hex)
    }

    private func fragmentoFirma() throws -> [String: Any] {
        try XCTUnwrap((try loadVectorsJSON())["fragmento_firma"] as? [String: Any])
    }

    private func fragmentVectors() throws -> [[String: Any]] {
        try XCTUnwrap((try fragmentoFirma())["fragments"] as? [[String: Any]])
    }

    private func packet(from fields: [String: Any]) throws -> FragmentoFirmaPacket {
        FragmentoFirmaPacket(
            deviceIdHash: hexToData(fields["device_id_hash"] as! String),
            ttl: UInt8(fields["ttl"] as! Int),
            fragmentIndex: UInt8(fields["frag_index"] as! Int),
            fragmentCount: UInt8(fields["frag_count"] as! Int),
            chunk: hexToData(fields["chunk_hex"] as! String)
        )
    }

    func testSharedVectorsFileDeclaresExpectedShape() throws {
        let vectors = try fragmentoFirma()
        XCTAssertEqual(vectors["version"] as? Int, 1)
        XCTAssertEqual(vectors["message_type"] as? Int, 3)
        XCTAssertEqual(vectors["packet_size_bytes"] as? Int, FragmentoFirmaPacket.packetSize)
        XCTAssertEqual(vectors["payload_chunk_size_bytes"] as? Int, FragmentoFirmaPacket.payloadChunkSize)
        XCTAssertEqual(vectors["fragment_count"] as? Int, 7)
        XCTAssertEqual((try fragmentVectors()).count, 7)
    }

    func testDecodeMatchesEveryVectorField() throws {
        for vector in try fragmentVectors() {
            let name = try XCTUnwrap(vector["name"] as? String)
            let fields = try XCTUnwrap(vector["fields"] as? [String: Any])
            let bytesHex = try XCTUnwrap(vector["bytes_hex"] as? String)

            let decoded = try XCTUnwrap(
                FragmentoFirmaPacketCodec.decode(hexToData(bytesHex)),
                "decode devolvió nil para el vector \(name)"
            )
            let expected = try packet(from: fields)
            XCTAssertEqual(decoded, expected, name)
        }
    }

    func testEncodeMatchesEveryVectorBytes() throws {
        for vector in try fragmentVectors() {
            let name = try XCTUnwrap(vector["name"] as? String)
            let fields = try XCTUnwrap(vector["fields"] as? [String: Any])
            let bytesHex = try XCTUnwrap(vector["bytes_hex"] as? String)

            let expected = try packet(from: fields)
            let encoded = FragmentoFirmaPacketCodec.encode(expected)
            XCTAssertEqual(encoded, hexToData(bytesHex), name)
        }
    }

    func testLastFragmentHasSixRealBytesRestHaveFifteen() throws {
        for vector in try fragmentVectors() {
            let fields = try XCTUnwrap(vector["fields"] as? [String: Any])
            let index = fields["frag_index"] as! Int
            let expectedLength = index == 6 ? 6 : 15
            XCTAssertEqual(fields["chunk_len"] as? Int, expectedLength)
        }
    }

    func testDecodeRejectsWrongPacketSize() throws {
        let bytesHex = try XCTUnwrap((try fragmentVectors().first)?["bytes_hex"] as? String)
        let truncated = hexToData(bytesHex).dropLast()
        XCTAssertNil(FragmentoFirmaPacketCodec.decode(truncated))
    }

    func testDecodeRejectsWrongMessageType() throws {
        let bytesHex = try XCTUnwrap((try fragmentVectors().first)?["bytes_hex"] as? String)
        var tampered = hexToData(bytesHex)
        tampered[2] = 0 // BEACON, no FRAGMENTO_FIRMA
        XCTAssertNil(FragmentoFirmaPacketCodec.decode(tampered))
    }

    func testDecodeRejectsIndexGreaterOrEqualToCount() throws {
        let bytesHex = try XCTUnwrap((try fragmentVectors().first)?["bytes_hex"] as? String)
        var tampered = hexToData(bytesHex)
        tampered[10] = FragmentoFirmaPacketCodec.fragHeader(index: 7, count: 7) // índice fuera de rango
        XCTAssertNil(FragmentoFirmaPacketCodec.decode(tampered))
    }
}
