import XCTest
@testable import PacketCodec

/// Compara el codec de `CaseBBeaconPacketCodec` (Versión=0x02) contra
/// `spec/test-vectors.json`, clave `case_b` (#39/#42) — mismo principio que
/// `VectorLoadingTests.swift` para el layout legado: fuente de verdad
/// compartida con Android, generada independientemente del codec de
/// ninguna plataforma.
final class CaseBVectorLoadingTests: XCTestCase {
    private func loadVectorsJSON() throws -> [String: Any] {
        try TestVectorFile.load()
    }

    private func hexToData(_ hex: String) -> Data {
        TestVectorFile.hexToData(hex)
    }

    private func packet(from fields: [String: Any]) throws -> CaseBBeaconPacket {
        CaseBBeaconPacket(
            messageType: try XCTUnwrap(BeaconPacket.MessageType(rawValue: UInt8(fields["message_type"] as! Int))),
            deviceIdHash: hexToData(fields["device_id_hash"] as! String),
            status: try XCTUnwrap(BeaconPacket.Status(rawValue: UInt8(fields["status"] as! Int))),
            latitudeE7: Int32(fields["latitude_e7"] as! Int),
            longitudeE7: Int32(fields["longitude_e7"] as! Int),
            timestamp: UInt32(fields["timestamp"] as! Int),
            ttl: UInt8(fields["ttl"] as! Int),
            mac: hexToData(fields["mac"] as! String),
            sequence: UInt8(fields["sequence"] as! Int)
        )
    }

    private func caseBVectors() throws -> [[String: Any]] {
        let json = try loadVectorsJSON()
        let caseB = try XCTUnwrap(json["case_b"] as? [String: Any])
        return try XCTUnwrap(caseB["vectors"] as? [[String: Any]])
    }

    func testSharedVectorsFileDeclaresExpectedShape() throws {
        let json = try loadVectorsJSON()
        let caseB = try XCTUnwrap(json["case_b"] as? [String: Any])
        XCTAssertEqual(caseB["version"] as? Int, 2)
        XCTAssertEqual(caseB["packet_size_bytes"] as? Int, CaseBBeaconPacket.packetSize)
        XCTAssertFalse((try caseBVectors()).isEmpty)
    }

    func testDecodeMatchesEveryVectorField() throws {
        for vector in try caseBVectors() {
            let name = try XCTUnwrap(vector["name"] as? String)
            let fields = try XCTUnwrap(vector["fields"] as? [String: Any])
            let bytesHex = try XCTUnwrap(vector["bytes_hex"] as? String)

            let decoded = try XCTUnwrap(
                CaseBBeaconPacketCodec.decode(hexToData(bytesHex)),
                "decode devolvió nil para el vector \(name)"
            )
            let expected = try packet(from: fields)
            XCTAssertEqual(decoded, expected, name)
        }
    }

    func testEncodeMatchesEveryVectorBytes() throws {
        for vector in try caseBVectors() {
            let name = try XCTUnwrap(vector["name"] as? String)
            let fields = try XCTUnwrap(vector["fields"] as? [String: Any])
            let bytesHex = try XCTUnwrap(vector["bytes_hex"] as? String)

            let expected = try packet(from: fields)
            let encoded = CaseBBeaconPacketCodec.encode(expected)
            XCTAssertEqual(encoded, hexToData(bytesHex), name)
        }
    }

    func testDecodeRejectsWrongPacketSize() throws {
        let bytesHex = try XCTUnwrap((try caseBVectors().first)?["bytes_hex"] as? String)
        let truncated = hexToData(bytesHex).dropLast()
        XCTAssertNil(CaseBBeaconPacketCodec.decode(truncated))
    }

    func testDecodeRejectsWrongVersion() throws {
        let bytesHex = try XCTUnwrap((try caseBVectors().first)?["bytes_hex"] as? String)
        var tampered = hexToData(bytesHex)
        tampered[1] = 0x01 // versión del layout legado, no de Caso B
        XCTAssertNil(CaseBBeaconPacketCodec.decode(tampered))
    }
}
