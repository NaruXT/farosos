import XCTest
@testable import BeaconRadio

final class BeaconGattServiceTests: XCTestCase {
    func testServiceAndCharacteristicUUIDsAreDistinct() {
        XCTAssertNotEqual(BeaconGattService.serviceUUID, BeaconGattService.characteristicUUID)
    }
}
