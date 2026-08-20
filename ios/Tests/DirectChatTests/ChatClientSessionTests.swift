import CryptoKit
import XCTest
@testable import DirectChat

final class ChatClientSessionTests: XCTestCase {
    func testStartHandshakeReturnsA32ByteEd25519PublicKey() {
        let session = ChatClientSession()
        XCTAssertEqual(session.startHandshake().count, 32)
    }

    func testEncryptOwnMessageReturnsNilBeforeTheHostPublicKeyArrives() {
        let session = ChatClientSession()
        _ = session.startHandshake()

        XCTAssertNil(session.encryptOwnMessage("hola", sentAt: 1))
    }

    func testAFullRoundTripBetweenAnIndependentEphemeralHostAndTheClientWorks() throws {
        let (hostPrivateKey, hostPublicKeyData) = EphemeralKeyAgreement.generateKeyPair()

        let session = ChatClientSession()
        let clientPublicKeyData = session.startHandshake()
        session.receivedHostPublicKey(hostPublicKeyData)

        guard let sealedMessage = session.encryptOwnMessage("necesito ayuda con la pierna", sentAt: 5) else {
            return XCTFail("se esperaba poder cifrar tras el handshake")
        }

        let hostKey = try EphemeralKeyAgreement.deriveSymmetricKey(ownPrivateKey: hostPrivateKey, peerPublicKeyData: clientPublicKeyData)
        let opened = ChatCipher.open(sealedMessage, using: hostKey)
        XCTAssertNotNil(opened)
        let decoded = ChatMessageWireFormat.decode(String(data: opened!, encoding: .utf8)!)
        XCTAssertEqual(decoded.first?.text, "necesito ayuda con la pierna")
    }

    func testReceivedEncryptedPayloadDeliversTheDecodedMessagesOnSuccess() throws {
        let (hostPrivateKey, hostPublicKeyData) = EphemeralKeyAgreement.generateKeyPair()
        let session = ChatClientSession()
        let clientPublicKeyData = session.startHandshake()
        session.receivedHostPublicKey(hostPublicKeyData)
        let hostKey = try EphemeralKeyAgreement.deriveSymmetricKey(ownPrivateKey: hostPrivateKey, peerPublicKeyData: clientPublicKeyData)

        let history = [ChatMessage(fromVictim: true, text: "hola, ya estoy cerca", sentAtEpochSeconds: 1)]
        let sealed = try ChatCipher.seal(Data(ChatMessageWireFormat.encode(history).utf8), using: hostKey)

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
