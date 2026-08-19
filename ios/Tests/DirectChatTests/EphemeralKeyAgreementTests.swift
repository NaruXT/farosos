import CryptoKit
import XCTest
@testable import DirectChat

/// Vector de X25519 verificado independientemente contra la librería
/// `cryptography` de Python (implementación estándar de RFC 7748, no
/// escrita por este proyecto) — mismo principio que los vectores de
/// `spec/test-vectors.json` (nunca hardcodeados a mano sin una segunda
/// fuente que los confirme). Confirma que `EphemeralKeyAgreement`
/// reproduce el mismo ECDH que una implementación de referencia externa
/// antes de aplicar HKDF encima.
final class EphemeralKeyAgreementTests: XCTestCase {
    private let scalarHex = "20498d8b722578bc9a81aebe1e85010ae94926942ebe3fdca5c8767dae3de95d"
    private let uCoordinateHex = "0d55bc39f8c9b3c02c2fac7ed22116b133255009851cd7359aecd1d10c40796d"
    private let expectedOutputHex = "6302df9dc6c8bf0a2d617d7584e902600502e29a91b5cad7a90e5cbd8c435d3e"

    func testRawSharedSecretMatchesAnIndependentlyVerifiedVector() throws {
        let privateKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: hexToData(scalarHex))
        let peerPublicKeyData = hexToData(uCoordinateHex)

        let raw = try EphemeralKeyAgreement.rawSharedSecret(ownPrivateKey: privateKey, peerPublicKeyData: peerPublicKeyData)

        XCTAssertEqual(raw, hexToData(expectedOutputHex))
    }

    func testGenerateKeyPairProducesA32ByteEd25519PublicKey() {
        let (_, publicKeyData) = EphemeralKeyAgreement.generateKeyPair()
        XCTAssertEqual(publicKeyData.count, 32)
    }

    func testBothSidesOfAFreshHandshakeDeriveTheSameSymmetricKey() throws {
        let (alicePrivate, alicePublicData) = EphemeralKeyAgreement.generateKeyPair()
        let (bobPrivate, bobPublicData) = EphemeralKeyAgreement.generateKeyPair()

        let aliceKey = try EphemeralKeyAgreement.deriveSymmetricKey(ownPrivateKey: alicePrivate, peerPublicKeyData: bobPublicData)
        let bobKey = try EphemeralKeyAgreement.deriveSymmetricKey(ownPrivateKey: bobPrivate, peerPublicKeyData: alicePublicData)

        XCTAssertEqual(aliceKey, bobKey)
    }

    func testTwoDifferentConnectionsNeverProduceTheSameSymmetricKey() throws {
        let (alicePrivate1, _) = EphemeralKeyAgreement.generateKeyPair()
        let (_, bobPublicData1) = EphemeralKeyAgreement.generateKeyPair()
        let key1 = try EphemeralKeyAgreement.deriveSymmetricKey(ownPrivateKey: alicePrivate1, peerPublicKeyData: bobPublicData1)

        let (alicePrivate2, _) = EphemeralKeyAgreement.generateKeyPair()
        let (_, bobPublicData2) = EphemeralKeyAgreement.generateKeyPair()
        let key2 = try EphemeralKeyAgreement.deriveSymmetricKey(ownPrivateKey: alicePrivate2, peerPublicKeyData: bobPublicData2)

        XCTAssertNotEqual(key1, key2)
    }

    private func hexToData(_ hex: String) -> Data {
        var data = Data()
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            data.append(UInt8(hex[index..<next], radix: 16)!)
            index = next
        }
        return data
    }
}
