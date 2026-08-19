import CryptoKit
import XCTest
@testable import DirectChat

final class ChatHostSessionTests: XCTestCase {
    private let ownHash = Data([0x01, 0x02, 0x03, 0x04, 0x05, 0x06])

    func testPeerConnectedReturnsAPublicKeyWhenNoConnectionIsActive() {
        let session = ChatHostSession(ownDeviceIdHash: ownHash)
        XCTAssertNotNil(session.peerConnected())
        XCTAssertTrue(session.hasActiveConnection)
    }

    func testPeerConnectedReturnsNilWhileAConnectionIsAlreadyActive() {
        let session = ChatHostSession(ownDeviceIdHash: ownHash)
        XCTAssertNotNil(session.peerConnected())

        XCTAssertNil(session.peerConnected(), "una segunda conexión debe rechazarse (#61: batería en el teléfono de la víctima)")
    }

    func testPeerDisconnectedFreesTheSlotForANewConnection() {
        let session = ChatHostSession(ownDeviceIdHash: ownHash)
        _ = session.peerConnected()

        session.peerDisconnected()

        XCTAssertFalse(session.hasActiveConnection)
        XCTAssertNotNil(session.peerConnected())
    }

    func testReceivedPeerPublicKeyReturnsNilWithoutAConnectedPeer() {
        let session = ChatHostSession(ownDeviceIdHash: ownHash)
        let (_, guestPublicKeyData) = EphemeralKeyAgreement.generateKeyPair()

        XCTAssertNil(session.receivedPeerPublicKey(guestPublicKeyData), "nadie se conectó todavía (peerConnected() nunca se llamó)")
    }

    func testAFullRoundTripBetweenHostAndAnIndependentEphemeralPeerWorks() throws {
        let history = [ChatMessage(senderDeviceIdHash: ownHash, text: "estado inicial", sentAt: 1)]
        let session = ChatHostSession(ownDeviceIdHash: ownHash, initialHistory: history)
        guard let hostPublicKeyData = session.peerConnected() else {
            return XCTFail("se esperaba una clave pública, sin conexión activa previa")
        }

        let (guestPrivateKey, guestPublicKeyData) = EphemeralKeyAgreement.generateKeyPair()
        guard let sealedHistory = session.receivedPeerPublicKey(guestPublicKeyData) else {
            return XCTFail("se esperaba el historial cifrado")
        }

        let guestKey = try EphemeralKeyAgreement.deriveSymmetricKey(ownPrivateKey: guestPrivateKey, peerPublicKeyData: hostPublicKeyData)
        let opened = ChatCipher.open(sealedHistory, using: guestKey)
        XCTAssertNotNil(opened)
        let decodedHistory = try JSONDecoder().decode([ChatMessage].self, from: opened!)
        XCTAssertEqual(decodedHistory, history)
    }

    func testReceivedEncryptedMessageAppendsToHistoryWhenItDecryptsCorrectly() throws {
        let session = ChatHostSession(ownDeviceIdHash: ownHash)
        guard let hostPublicKeyData = session.peerConnected() else { return XCTFail() }
        let (guestPrivateKey, guestPublicKeyData) = EphemeralKeyAgreement.generateKeyPair()
        _ = session.receivedPeerPublicKey(guestPublicKeyData)
        let guestKey = try EphemeralKeyAgreement.deriveSymmetricKey(ownPrivateKey: guestPrivateKey, peerPublicKeyData: hostPublicKeyData)

        let guestHash = Data([0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff])
        let incoming = ChatMessage(senderDeviceIdHash: guestHash, text: "estoy consciente", sentAt: 2)
        let sealed = try ChatCipher.seal(JSONEncoder().encode(incoming), using: guestKey)

        var observedHistory: [ChatMessage]?
        session.onHistoryChanged = { observedHistory = $0 }
        session.receivedEncryptedMessage(sealed)

        XCTAssertEqual(session.history, [incoming])
        XCTAssertEqual(observedHistory, [incoming])
    }

    func testReceivedEncryptedMessageIsIgnoredWithoutAnEstablishedSessionKey() {
        let session = ChatHostSession(ownDeviceIdHash: ownHash)
        _ = session.peerConnected() // conectado, pero sin intercambio de clave todavía

        session.receivedEncryptedMessage(Data([0x01, 0x02, 0x03]))

        XCTAssertTrue(session.history.isEmpty)
    }

    func testSendOwnMessageReturnsNilWithoutAnEstablishedSessionKey() {
        let session = ChatHostSession(ownDeviceIdHash: ownHash)
        XCTAssertNil(session.sendOwnMessage("hola", sentAt: 1), "no hay conexión, no debería poder mandar nada")
    }

    func testSendOwnMessageAppendsToHistoryAndReturnsAnEncryptedSingleElementList() throws {
        let session = ChatHostSession(ownDeviceIdHash: ownHash)
        guard let hostPublicKeyData = session.peerConnected() else { return XCTFail() }
        let (guestPrivateKey, guestPublicKeyData) = EphemeralKeyAgreement.generateKeyPair()
        _ = session.receivedPeerPublicKey(guestPublicKeyData)
        let guestKey = try EphemeralKeyAgreement.deriveSymmetricKey(ownPrivateKey: guestPrivateKey, peerPublicKeyData: hostPublicKeyData)

        guard let sealed = session.sendOwnMessage("voy a resistir", sentAt: 3) else { return XCTFail() }

        XCTAssertEqual(session.history.last?.text, "voy a resistir")
        let opened = ChatCipher.open(sealed, using: guestKey)
        let decoded = try JSONDecoder().decode([ChatMessage].self, from: opened!)
        XCTAssertEqual(decoded.count, 1)
        XCTAssertEqual(decoded.first?.text, "voy a resistir")
    }
}
