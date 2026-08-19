import CryptoKit
import XCTest
@testable import DirectChat

final class ChatCipherTests: XCTestCase {
    func testSealThenOpenRoundTrips() throws {
        let key = SymmetricKey(size: .bits256)
        let plaintext = Data("necesito agua, estamos 3 personas".utf8)

        let sealed = try ChatCipher.seal(plaintext, using: key)
        let opened = ChatCipher.open(sealed, using: key)

        XCTAssertEqual(opened, plaintext)
    }

    func testOpenReturnsNilWithTheWrongKey() throws {
        let sealed = try ChatCipher.seal(Data("hola".utf8), using: SymmetricKey(size: .bits256))

        let opened = ChatCipher.open(sealed, using: SymmetricKey(size: .bits256))

        XCTAssertNil(opened)
    }

    func testOpenReturnsNilOnCorruptedData() {
        let opened = ChatCipher.open(Data([0x01, 0x02, 0x03]), using: SymmetricKey(size: .bits256))
        XCTAssertNil(opened)
    }

    func testTwoSealsOfTheSamePlaintextProduceDifferentCiphertext() throws {
        let key = SymmetricKey(size: .bits256)
        let plaintext = Data("mismo texto".utf8)

        let sealed1 = try ChatCipher.seal(plaintext, using: key)
        let sealed2 = try ChatCipher.seal(plaintext, using: key)

        XCTAssertNotEqual(sealed1, sealed2, "el nonce aleatorio de ChaChaPoly debe variar entre sellos")
    }
}
