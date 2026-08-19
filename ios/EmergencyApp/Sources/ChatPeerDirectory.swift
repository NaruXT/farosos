import CoreBluetooth
import Foundation

/// "Lo que este teléfono sabe" de a qué `CBPeripheral` corresponde cada
/// `device_id_hash` visto por `BleScanner` — necesario para que abrir el
/// chat de un caso elegido en "Casos" (#61/#62) sepa a qué periférico
/// reconectarse. Retiene los `CBPeripheral` fuertemente a propósito (igual
/// que `BleScanner.connectingPeripherals`): CoreBluetooth no los mantiene
/// vivos por su cuenta entre desconexión y reconexión.
final class ChatPeerDirectory {
    private var peripheralsByDeviceIdHash: [Data: CBPeripheral] = [:]

    func record(deviceIdHash: Data, peripheral: CBPeripheral) {
        peripheralsByDeviceIdHash[deviceIdHash] = peripheral
    }

    func peripheral(for deviceIdHash: Data) -> CBPeripheral? {
        peripheralsByDeviceIdHash[deviceIdHash]
    }
}
