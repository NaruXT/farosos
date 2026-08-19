import XCTest
@testable import PacketCodec

/// Compara `CaseBAuthentication` (ECDH + MAC de Caso B) contra
/// `spec/test-vectors.json`, claves `ecdh`/`mac_vectors` (#39/#42).
final class CaseBAuthenticationVectorTests: XCTestCase {
    private func loadVectorsJSON() throws -> [String: Any] {
        try TestVectorFile.load()
    }

    private func hexToData(_ hex: String) -> Data {
        TestVectorFile.hexToData(hex)
    }

    func testDeriveKSharedMatchesEveryVector() throws {
        let json = try loadVectorsJSON()
        let ecdh = try XCTUnwrap(json["ecdh"] as? [String: Any])
        let vectors = try XCTUnwrap(ecdh["vectors"] as? [[String: Any]])
        XCTAssertFalse(vectors.isEmpty)

        for vector in vectors {
            let name = try XCTUnwrap(vector["name"] as? String)
            let deviceSeed = hexToData(try XCTUnwrap(vector["device_secret_key_ed25519_hex"] as? String))
            let backendPub = hexToData(try XCTUnwrap(vector["backend_public_key_x25519_hex"] as? String))
            let expected = hexToData(try XCTUnwrap(vector["expected_k_shared_hex"] as? String))

            let kShared = CaseBAuthentication.deriveKShared(
                devicePrivateKeyEd25519Seed: deviceSeed,
                backendPublicKeyX25519: backendPub
            )
            XCTAssertEqual(kShared, expected, name)
        }
    }

    func testComputeMacMatchesEveryVector() throws {
        let json = try loadVectorsJSON()
        let vectors = try XCTUnwrap(json["mac_vectors"] as? [[String: Any]])
        XCTAssertFalse(vectors.isEmpty)

        for vector in vectors {
            let name = try XCTUnwrap(vector["name"] as? String)
            let kShared = hexToData(try XCTUnwrap(vector["k_shared_hex"] as? String))
            let content = hexToData(try XCTUnwrap(vector["content_hex"] as? String))
            let expected = hexToData(try XCTUnwrap(vector["expected_mac_hex"] as? String))

            XCTAssertEqual(CaseBAuthentication.computeMac(kShared: kShared, content: content), expected, name)
        }
    }

    func testAuthenticatedContentMatchesEveryCaseBVector() throws {
        let json = try loadVectorsJSON()
        let caseB = try XCTUnwrap(json["case_b"] as? [String: Any])
        let vectors = try XCTUnwrap(caseB["vectors"] as? [[String: Any]])

        for vector in vectors {
            let name = try XCTUnwrap(vector["name"] as? String)
            let fields = try XCTUnwrap(vector["fields"] as? [String: Any])
            let expectedContentHex = try XCTUnwrap(vector["content_hex"] as? String)

            let packet = CaseBBeaconPacket(
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

            let content = CaseBAuthentication.authenticatedContent(from: packet)
            XCTAssertEqual(content, hexToData(expectedContentHex), name)
        }
    }

    /// AC: cambiar cualquier campo autenticado cambia el MAC resultante.
    func testChangingAnyAuthenticatedFieldChangesTheMac() throws {
        let kShared = Data(repeating: 0x11, count: 32)
        let base = CaseBBeaconPacket(
            messageType: .beacon,
            deviceIdHash: Data([0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff]),
            status: .ok,
            latitudeE7: 194326000,
            longitudeE7: -991332000,
            timestamp: 1700010000,
            ttl: 16,
            mac: Data(repeating: 0, count: 4),
            sequence: 0
        )
        let baseMac = CaseBAuthentication.computeMac(
            kShared: kShared,
            content: CaseBAuthentication.authenticatedContent(from: base)
        )

        var changedDeviceIdHash = base
        changedDeviceIdHash.deviceIdHash = Data([0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0x00])
        var changedStatus = base
        changedStatus.status = .ayuda
        var changedLatitude = base
        changedLatitude.latitudeE7 = base.latitudeE7 + 1
        var changedLongitude = base
        changedLongitude.longitudeE7 = base.longitudeE7 + 1
        var changedTimestamp = base
        changedTimestamp.timestamp = base.timestamp + 1
        var changedTtl = base
        changedTtl.ttl = base.ttl - 1
        var changedSequence = base
        changedSequence.sequence = base.sequence + 1

        let variants: [(String, CaseBBeaconPacket)] = [
            ("deviceIdHash", changedDeviceIdHash),
            ("status", changedStatus),
            ("latitude", changedLatitude),
            ("longitude", changedLongitude),
            ("timestamp", changedTimestamp),
            ("ttl", changedTtl),
            ("sequence", changedSequence),
        ]
        for (name, variant) in variants {
            let mac = CaseBAuthentication.computeMac(
                kShared: kShared,
                content: CaseBAuthentication.authenticatedContent(from: variant)
            )
            XCTAssertNotEqual(mac, baseMac, "cambiar \(name) debería cambiar el MAC")
        }
    }
}
