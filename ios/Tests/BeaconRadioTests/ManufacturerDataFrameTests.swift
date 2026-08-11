import XCTest
import PacketCodec
@testable import BeaconRadio

final class ManufacturerDataFrameTests: XCTestCase {
    private func samplePacket() -> BeaconPacket {
        BeaconPacket(
            messageType: .beacon,
            deviceIdHash: Data([1, 2, 3, 4, 5, 6]),
            status: .ok,
            latitudeE7: 123,
            longitudeE7: -456,
            timestamp: 1_700_000_000,
            ttl: 16,
            nonce: 0xBEEF,
            sequence: 7
        )
    }

    func testEncodedValueSizeFitsLegacyAdvertisingBudget() {
        let data = ManufacturerDataFrame.encode(samplePacket())

        // Company ID (2) + payload (26) = 28 bytes de "value"; CoreBluetooth
        // antepone length(1)+AD type(1) al armar el advertisement real, para
        // un total de 30 de los 31 bytes del límite legacy.
        XCTAssertEqual(data.count, 28)
    }

    func testDecodeRoundTripsThroughEncode() {
        let packet = samplePacket()
        let data = ManufacturerDataFrame.encode(packet)

        XCTAssertEqual(ManufacturerDataFrame.decode(data), packet)
    }

    func testDecodeRejectsWrongCompanyId() {
        var data = ManufacturerDataFrame.encode(samplePacket())
        data[0] = 0x00
        data[1] = 0x00

        XCTAssertNil(ManufacturerDataFrame.decode(data))
    }

    func testDecodeRejectsWrongLength() {
        let data = ManufacturerDataFrame.encode(samplePacket()).dropLast()

        XCTAssertNil(ManufacturerDataFrame.decode(Data(data)))
    }

    func testDecodeRejectsPayloadWithWrongMagicOrVersion() {
        // El propio BeaconPacketCodec.decode ya filtra Magic/Versión — este
        // test confirma que ManufacturerDataFrame no reimplementa ese
        // filtro y simplemente delega en él (sin ruta especial).
        var data = ManufacturerDataFrame.encode(samplePacket())
        data[2] = 0x00 // corrompe el byte de Magic dentro del payload

        XCTAssertNil(ManufacturerDataFrame.decode(data))
    }
}
