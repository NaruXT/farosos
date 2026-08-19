import CryptoKit
import XCTest
@testable import DirectChat

final class ChatClientSessionTests: XCTestCase {
    private let ownHash = Data([0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff])

    func testStartHandshakeReturnsA32ByteEd25519PublicKey() {
        let session = ChatClientSession()
        XCTAssertEqual(session.startHandshake().count, 32)
    }

    func testEncryptOwnMessageReturnsNilBeforeTheHostPublicKeyArrives() {
        let session = ChatClientSession()
        _ = session.startHandshake()

        XCTAssertNil(session.encryptOwnMessage("hola", ownDeviceIdHash: ownHash, sentAt: 1))
    }

    func testAFullRoundTripBetweenAnIndependentEphemeralHostAndTheClientWorks() throws {
        let (hostPrivateKey, hostPublicKeyData) = EphemeralKeyAgreement.generateKeyPair()

        let session = ChatClientSession()
        let clientPublicKeyData = session.startHandshake()
        session.receivedHostPublicKey(hostPublicKeyData)

        guard let sealedMessage = session.encryptOwnMessage("necesito ayuda con la pierna", ownDeviceIdHash: ownHash, sentAt: 5) else {
            return XCTFail("se esperaba poder cifrar tras el handshake")
        }

        let hostKey = try EphemeralKeyAgreement.deriveSymmetricKey(ownPrivateKey: hostPrivateKey, peerPublicKeyData: clientPublicKeyData)
        let opened = ChatCipher.open(sealedMessage, using: hostKey)
        XCTAssertNotNil(opened)
        let decoded = try JSONDecoder().decode([ChatMessage].self, from: opened!)
        XCTAssertEqual(decoded.first?.text, "necesito ayuda con la pierna")
    }

    func testReceivedEncryptedPayloadDeliversTheDecodedMessagesOnSuccess() throws {
        let (hostPrivateKey, hostPublicKeyData) = EphemeralKeyAgreement.generateKeyPair()
        let session = ChatClientSession()
        let clientPublicKeyData = session.startHandshake()
        session.receivedHostPublicKey(hostPublicKeyData)
        let hostKey = try EphemeralKeyAgreement.deriveSymmetricKey(ownPrivateKey: hostPrivateKey, peerPublicKeyData: clientPublicKeyData)

        let history = [ChatMessage(senderDeviceIdHash: ownHash, text: "hola, ya estoy cerca", sentAt: 1)]
        let sealed = try ChatCipher.seal(JSONEncoder().encode(history), using: hostKey)

        var received: [ChatMessage]?
        session.onMessagesReceived = { received = $0 }
        session.receivedEncryptedPayload(sealed)

        XCTAssertEqual(received, history)
    }

    func testReceivedEncryptedPayloadIsIgnoredWithoutAnEstablishedSessionKey() {
        let session = ChatClientSession()
        _ = session.startHandshake() // sin recibir la clave del host todavía

        var received: [ChatMessage]?
        session.onMessagesReceived = { received = $0 }
        session.receivedEncryptedPayload(Data([0x01, 0x02, 0x03]))

        XCTAssertNil(received)
    }
}
