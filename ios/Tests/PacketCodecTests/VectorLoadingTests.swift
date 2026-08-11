import XCTest
@testable import PacketCodec

/// Compara el codec de `BeaconPacketCodec` contra `spec/test-vectors.json`,
/// la fuente de verdad compartida con el codec de Android (generada de forma
/// independiente con `struct` de Python, no derivada del código de ninguna
/// plataforma). Un round-trip aislado por plataforma no alcanza — esto valida
/// el contrato de bytes exacto que Swift y Kotlin deben compartir.
final class VectorLoadingTests: XCTestCase {
    private func repoRootURL() -> URL {
        // ios/Tests/PacketCodecTests/VectorLoadingTests.swift -> raíz del repo
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent() // PacketCodecTests/
            .deletingLastPathComponent() // Tests/
            .deletingLastPathComponent() // ios/
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

    private func hexByte(_ hex: String) -> UInt8 {
        let digits = hex.hasPrefix("0x") ? String(hex.dropFirst(2)) : hex
        return UInt8(digits, radix: 16)!
    }

    /// Construye el `BeaconPacket` esperado a partir del diccionario `fields`
    /// de un vector — única fuente de la conversión JSON -> tipos, compartida
    /// por el test de decode (comparación por Equatable) y el de encode.
    private func packet(from fields: [String: Any]) throws -> BeaconPacket {
        BeaconPacket(
            messageType: try XCTUnwrap(BeaconPacket.MessageType(rawValue: UInt8(fields["message_type"] as! Int))),
            deviceIdHash: hexToData(fields["device_id_hash"] as! String),
            status: try XCTUnwrap(BeaconPacket.Status(rawValue: UInt8(fields["status"] as! Int))),
            latitudeE7: Int32(fields["latitude_e7"] as! Int),
            longitudeE7: Int32(fields["longitude_e7"] as! Int),
            timestamp: UInt32(fields["timestamp"] as! Int),
            ttl: UInt8(fields["ttl"] as! Int),
            nonce: UInt16(fields["nonce"] as! Int),
            sequence: UInt8(fields["sequence"] as! Int)
        )
    }

    func testSharedVectorsFileIsReadableAndNonEmpty() throws {
        let json = try loadVectorsJSON()
        let vectors = try XCTUnwrap(json["vectors"] as? [[String: Any]])

        XCTAssertEqual(json["byte_order"] as? String, "little-endian")
        XCTAssertEqual(json["packet_size_bytes"] as? Int, BeaconPacket.packetSize)
        XCTAssertFalse(vectors.isEmpty)
    }

    func testDecodeMatchesEveryVectorField() throws {
        let json = try loadVectorsJSON()
        let vectors = try XCTUnwrap(json["vectors"] as? [[String: Any]])

        for vector in vectors {
            let name = try XCTUnwrap(vector["name"] as? String)
            let fields = try XCTUnwrap(vector["fields"] as? [String: Any])
            let bytesHex = try XCTUnwrap(vector["bytes_hex"] as? String)

            // magic/versión no son propiedades de BeaconPacket (son constantes
            // fijas del protocolo), así que se comparan aparte contra `fields`.
            XCTAssertEqual(hexByte(fields["magic"] as! String), BeaconPacket.magic, name)
            XCTAssertEqual(UInt8(fields["version"] as! Int), BeaconPacket.version, name)

            let decoded = try XCTUnwrap(
                BeaconPacketCodec.decode(hexToData(bytesHex)),
                "decode devolvió nil para el vector \(name)"
            )
            let expected = try packet(from: fields)
            XCTAssertEqual(decoded, expected, name)
        }
    }

    func testEncodeMatchesEveryVectorBytes() throws {
        let json = try loadVectorsJSON()
        let vectors = try XCTUnwrap(json["vectors"] as? [[String: Any]])

        for vector in vectors {
            let name = try XCTUnwrap(vector["name"] as? String)
            let fields = try XCTUnwrap(vector["fields"] as? [String: Any])
            let bytesHex = try XCTUnwrap(vector["bytes_hex"] as? String)

            let expected = try packet(from: fields)
            let encoded = BeaconPacketCodec.encode(expected)
            XCTAssertEqual(encoded, hexToData(bytesHex), name)
        }
    }
}
