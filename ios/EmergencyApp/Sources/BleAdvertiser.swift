import BeaconRadio
import CoreBluetooth
import Foundation

/// Envoltorio de `CBPeripheralManager` para publicar el beacon actual del
/// nodo. iOS no puede anunciar Manufacturer Specific Data como periférico
/// (ver `BeaconGattService`), así que el advertisement solo señaliza "soy
/// un nodo Farosos" (Service UUID + Local Name) y el `BeaconPacket`
/// completo viaja como el valor de una característica GATT de solo
/// lectura.
final class BleAdvertiser: NSObject, CBPeripheralManagerDelegate {
    var onError: ((String) -> Void)?

    private lazy var peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
    private var currentData: Data?
    private var isServiceAdded = false

    /// Reemplaza el payload que este nodo publica. El valor de la
    /// característica se resuelve dinámicamente en `didReceiveRead` a
    /// partir de `currentData`, así que no hace falta reiniciar el
    /// advertising cuando el paquete cambia — solo la primera vez, para
    /// registrar el servicio GATT.
    func updateAdvertisedData(_ data: Data) {
        currentData = data
        guard peripheralManager.state == .poweredOn, !isServiceAdded else { return }
        setUpService()
    }

    private func setUpService() {
        let characteristic = CBMutableCharacteristic(
            type: BeaconGattService.characteristicUUID,
            properties: [.read],
            value: nil,
            permissions: [.readable]
        )
        let service = CBMutableService(type: BeaconGattService.serviceUUID, primary: true)
        service.characteristics = [characteristic]
        peripheralManager.add(service)
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard peripheral.state == .poweredOn else {
            // Un power-cycle real de Bluetooth invalida los servicios GATT ya
            // agregados — hay que re-agregarlo cuando vuelva a poweredOn, no
            // solo reanudar el advertising.
            isServiceAdded = false
            return
        }
        if isServiceAdded {
            startAdvertising()
        } else if currentData != nil {
            setUpService()
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error {
            onError?("Error al agregar el servicio GATT: \(error.localizedDescription)")
            return
        }
        isServiceAdded = true
        startAdvertising()
    }

    private func startAdvertising() {
        peripheralManager.stopAdvertising()
        peripheralManager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [BeaconGattService.serviceUUID],
            CBAdvertisementDataLocalNameKey: "Farosos"
        ])
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        guard let error else { return }
        onError?("Error al iniciar advertising: \(error.localizedDescription)")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        guard request.characteristic.uuid == BeaconGattService.characteristicUUID else {
            peripheralManager.respond(to: request, withResult: .attributeNotFound)
            return
        }
        guard let data = currentData, request.offset <= data.count else {
            peripheralManager.respond(to: request, withResult: .invalidOffset)
            return
        }
        request.value = data.subdata(in: request.offset..<data.count)
        peripheralManager.respond(to: request, withResult: .success)
    }
}
