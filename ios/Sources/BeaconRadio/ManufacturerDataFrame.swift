import Foundation
import PacketCodec

/// Envoltorio de "Manufacturer Specific Data" (AD type `0xFF`) descrito en
/// `spec/packet-format.md`. Esto es el *value* que se entrega a
/// `CBAdvertisementDataManufacturerDataKey` / se lee de vuelta desde ahí —
/// CoreBluetooth antepone por su cuenta el byte de longitud y el AD type al
/// armar el advertisement real, así que aquí solo va Company ID + payload.
public enum ManufacturerDataFrame {
    public static let companyId: UInt16 = 0xFFFF

    public static func encode(_ packet: BeaconPacket) -> Data {
        var data = Data(capacity: 2 + BeaconPacket.packetSize)
        data.append(UInt8(companyId & 0xFF))
        data.append(UInt8((companyId >> 8) & 0xFF))
        data.append(BeaconPacketCodec.encode(packet))
        return data
    }

    /// Filtra por Company ID y delega el resto (incluyendo Magic/Versión) en
    /// `BeaconPacketCodec.decode` — sin reimplementar ese filtro aquí.
    public static func decode(_ data: Data) -> BeaconPacket? {
        guard data.count == 2 + BeaconPacket.packetSize else { return nil }
        let base = data.startIndex
        let receivedCompanyId = UInt16(data[base]) | (UInt16(data[base + 1]) << 8)
        guard receivedCompanyId == companyId else { return nil }
        return BeaconPacketCodec.decode(data.subdata(in: (base + 2)..<data.endIndex))
    }
}
