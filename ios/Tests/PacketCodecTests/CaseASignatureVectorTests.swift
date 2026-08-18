import CryptoKit
import XCTest
@testable import PacketCodec

/// Compara `CaseASignature` (autocertificado de Caso A) contra
/// `spec/test-vectors.json`, clave `fragmento_firma.identity` (#38/#44).
final class CaseASignatureVectorTests: XCTestCase {
    private func loadVectorsJSON() throws -> [String: Any] {
        try TestVectorFile.load()
    }

    private func hexToData(_ hex: String) -> Data {
        TestVectorFile.hexToData(hex)
    }

    private func identity() throws -> [String: Any] {
        let fragmentoFirma = try XCTUnwrap((try loadVectorsJSON())["fragmento_firma"] as? [String: Any])
        return try XCTUnwrap(fragmentoFirma["identity"] as? [String: Any])
    }

    /// CryptoKit firma Ed25519 con un nonce aleatorio por firma (ver el
    /// comentario de `CaseASignature`) — no se puede comparar byte a byte
    /// contra `signature_hex` (generado con la firma determinística de
    /// RFC 8032 de `@noble/curves`). En su lugar: la clave privada
    /// reconstruida desde `device_secret_key_ed25519_hex` debe derivar la
    /// misma pubkey del vector, y `sign()` debe producir una firma que
    /// `verify()` acepte contra esa pubkey.
    func testSignProducesASignatureThatVerifiesAndMatchesTheVectorPublicKey() throws {
        let identity = try identity()
        let seed = hexToData(try XCTUnwrap(identity["device_secret_key_ed25519_hex"] as? String))
        let expectedPublicKey = hexToData(try XCTUnwrap(identity["device_public_key_ed25519_hex"] as? String))

        let privateKey = try Curve25519.Signing.PrivateKey(rawRepresentation: seed)
        XCTAssertEqual(privateKey.publicKey.rawRepresentation, expectedPublicKey)

        let signature = CaseASignature.sign(privateKey: privateKey)
        XCTAssertTrue(CaseASignature.verify(publicKey: expectedPublicKey, signature: signature))
    }

    func testVerifyAcceptsTheVectorSignature() throws {
        let identity = try identity()
        let publicKey = hexToData(try XCTUnwrap(identity["device_public_key_ed25519_hex"] as? String))
        let signature = hexToData(try XCTUnwrap(identity["signature_hex"] as? String))

        XCTAssertTrue(CaseASignature.verify(publicKey: publicKey, signature: signature))
    }

    func testVerifyRejectsATamperedSignature() throws {
        let identity = try identity()
        let publicKey = hexToData(try XCTUnwrap(identity["device_public_key_ed25519_hex"] as? String))
        var tampered = hexToData(try XCTUnwrap(identity["signature_hex"] as? String))
        tampered[0] ^= 0xFF

        XCTAssertFalse(CaseASignature.verify(publicKey: publicKey, signature: tampered))
    }

    func testVerifyRejectsASignatureFromADifferentIdentity() throws {
        let identity = try identity()
        let publicKey = hexToData(try XCTUnwrap(identity["device_public_key_ed25519_hex"] as? String))

        let otherPrivateKey = Curve25519.Signing.PrivateKey()
        let otherSignature = CaseASignature.sign(privateKey: otherPrivateKey)

        XCTAssertFalse(CaseASignature.verify(publicKey: publicKey, signature: otherSignature))
    }
}
