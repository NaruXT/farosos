import XCTest
@testable import PacketCodec

/// Compara `SignatureFragmenter.fragment` contra `spec/test-vectors.json`,
/// clave `fragmento_firma` (#38/#44), y verifica el round-trip
/// fragment/reassemble con datos arbitrarios.
final class SignatureFragmenterTests: XCTestCase {
    private func hexToData(_ hex: String) -> Data {
        TestVectorFile.hexToData(hex)
    }

    private func fragmentoFirma() throws -> [String: Any] {
        try XCTUnwrap((try TestVectorFile.load())["fragmento_firma"] as? [String: Any])
    }

    func testFragmentMatchesEveryVectorFragment() throws {
        let vectors = try fragmentoFirma()
        let identity = try XCTUnwrap(vectors["identity"] as? [String: Any])
        let publicKey = hexToData(try XCTUnwrap(identity["device_public_key_ed25519_hex"] as? String))
        let signature = hexToData(try XCTUnwrap(identity["signature_hex"] as? String))
        let deviceIdHash = hexToData(try XCTUnwrap(identity["device_id_hash"] as? String))
        let fragmentVectors = try XCTUnwrap(vectors["fragments"] as? [[String: Any]])
        let ttl = UInt8((try XCTUnwrap(fragmentVectors.first?["fields"] as? [String: Any]))["ttl"] as! Int)

        let fragments = SignatureFragmenter.fragment(publicKey: publicKey, signature: signature, deviceIdHash: deviceIdHash, ttl: ttl)
        XCTAssertEqual(fragments.count, 7)

        for vector in fragmentVectors {
            let fields = try XCTUnwrap(vector["fields"] as? [String: Any])
            let index = UInt8(fields["frag_index"] as! Int)
            let expected = FragmentoFirmaPacket(
                deviceIdHash: deviceIdHash,
                ttl: ttl,
                fragmentIndex: index,
                fragmentCount: UInt8(fields["frag_count"] as! Int),
                chunk: hexToData(fields["chunk_hex"] as! String)
            )
            XCTAssertEqual(fragments[Int(index)], expected, vector["name"] as? String ?? "")
        }
    }

    func testReassembleFromVectorFragmentsRecoversTheOriginalPayload() throws {
        let vectors = try fragmentoFirma()
        let expectedPayload = hexToData(try XCTUnwrap(vectors["payload_hex"] as? String))
        let fragmentVectors = try XCTUnwrap(vectors["fragments"] as? [[String: Any]])
        let identity = try XCTUnwrap(vectors["identity"] as? [String: Any])
        let deviceIdHash = hexToData(try XCTUnwrap(identity["device_id_hash"] as? String))

        let fragments = try fragmentVectors.map { vector -> FragmentoFirmaPacket in
            let fields = try XCTUnwrap(vector["fields"] as? [String: Any])
            return FragmentoFirmaPacket(
                deviceIdHash: deviceIdHash,
                ttl: UInt8(fields["ttl"] as! Int),
                fragmentIndex: UInt8(fields["frag_index"] as! Int),
                fragmentCount: UInt8(fields["frag_count"] as! Int),
                chunk: hexToData(fields["chunk_hex"] as! String)
            )
        }

        XCTAssertEqual(SignatureFragmenter.reassemble(fragments), expectedPayload)
    }

    func testReassembleWorksRegardlessOfFragmentOrder() throws {
        let publicKey = Data((0..<32).map { UInt8($0) })
        let signature = Data((0..<64).map { UInt8(255 - $0) })
        let deviceIdHash = Data([1, 2, 3, 4, 5, 6])

        let fragments = SignatureFragmenter.fragment(publicKey: publicKey, signature: signature, deviceIdHash: deviceIdHash, ttl: 16)
        let shuffled = fragments.shuffled()

        XCTAssertEqual(SignatureFragmenter.reassemble(shuffled), publicKey + signature)
    }

    func testReassembleReturnsNilWhenFragmentsAreMissing() throws {
        let publicKey = Data(repeating: 0xAA, count: 32)
        let signature = Data(repeating: 0xBB, count: 64)
        let deviceIdHash = Data([1, 2, 3, 4, 5, 6])

        let fragments = SignatureFragmenter.fragment(publicKey: publicKey, signature: signature, deviceIdHash: deviceIdHash, ttl: 16)
        let incomplete = fragments.dropLast()

        XCTAssertNil(SignatureFragmenter.reassemble(Array(incomplete)))
    }

    func testReassembleReturnsNilWhenTwoFragmentsWithTheSameIndexDisagree() throws {
        let publicKey = Data(repeating: 0xAA, count: 32)
        let signature = Data(repeating: 0xBB, count: 64)
        let deviceIdHash = Data([1, 2, 3, 4, 5, 6])

        var fragments = SignatureFragmenter.fragment(publicKey: publicKey, signature: signature, deviceIdHash: deviceIdHash, ttl: 16)
        var tampered = fragments[0]
        tampered.chunk = Data(repeating: 0xFF, count: tampered.chunk.count)
        fragments.append(tampered) // mismo índice que fragments[0], contenido distinto

        XCTAssertNil(SignatureFragmenter.reassemble(fragments))
    }

    func testReassembleReturnsNilForEmptyInput() {
        XCTAssertNil(SignatureFragmenter.reassemble([]))
    }

    func testSplitIsTheInverseOfConcatenatingPublicKeyAndSignature() throws {
        let vectors = try fragmentoFirma()
        let identity = try XCTUnwrap(vectors["identity"] as? [String: Any])
        let publicKey = hexToData(try XCTUnwrap(identity["device_public_key_ed25519_hex"] as? String))
        let signature = hexToData(try XCTUnwrap(identity["signature_hex"] as? String))
        let payload = hexToData(try XCTUnwrap(vectors["payload_hex"] as? String))

        let split = try XCTUnwrap(SignatureFragmenter.split(payload))
        XCTAssertEqual(split.publicKey, publicKey)
        XCTAssertEqual(split.signature, signature)
    }

    func testSplitReturnsNilForAPayloadOfTheWrongSize() {
        XCTAssertNil(SignatureFragmenter.split(Data(repeating: 0, count: 95)))
        XCTAssertNil(SignatureFragmenter.split(Data(repeating: 0, count: 97)))
    }
}
