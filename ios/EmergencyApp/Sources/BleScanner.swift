import BeaconRadio
import CoreBluetooth
import Foundation

/// Envoltorio de `CBCentralManager` para escanear advertisements BLE
/// cercanos. Dos rutas de recepción:
///
/// - **Manufacturer Specific Data directa**: peers (p. ej. Android) que sí
///   pueden anunciar Manufacturer Data como periférico — se decodifica de
///   inmediato desde el propio advertisement, sin conexión.
/// - **GATT**: peers iOS, que solo pueden señalizar su presencia via
///   `BeaconGattService.serviceUUID` (ver ese tipo para el porqué) — hay
///   que conectarse, descubrir la característica y leerla para obtener el
///   `BeaconPacket` real.
///
/// Escanea con `allowDuplicates: true` a propósito: por defecto
/// `CBCentralManager` ya colapsa advertisements idénticos repetidos, lo que
/// enmascararía la caché de dedup de la app (decisión 12) en vez de
/// ejercitarla.
final class BleScanner: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    /// El `CBPeripheral` acompaña cada paquete recibido (#61/#62) — quien
    /// decodifica (`EmergencyViewModel`) es quien sabe el `device_id_hash`
    /// real, así que es ahí donde se arma el directorio peripheral↔hash que
    /// el chat necesita para poder reconectarse más tarde a un caso
    /// elegido desde "Casos". `BleScanner` mismo no decodifica nada, solo
    /// reenvía el peripheral con el que llegó cada paquete.
    var onManufacturerData: ((Data, CBPeripheral) -> Void)?
    var onGattPacketData: ((Data, CBPeripheral) -> Void)?
    var onError: ((String) -> Void)?

    /// Si un peer acepta la conexión pero nunca completa el descubrimiento
    /// de servicio/característica ni la lectura (p. ej. se aleja o se
    /// cuelga a medio camino), esta ventana evita que quede una conexión
    /// abierta indefinidamente — relevante porque iOS limita cuántas
    /// conexiones BLE simultáneas admite.
    private static let connectionTimeout: TimeInterval = 5

    private lazy var centralManager = CBCentralManager(delegate: self, queue: nil)
    /// `CBPeripheral` no se mantiene vivo por su cuenta durante una
    /// conexión — hay que retenerlo mientras dura, o CoreBluetooth la corta.
    private var connectingPeripherals: Set<CBPeripheral> = []

    func start() {
        if centralManager.state == .poweredOn {
            startScanning()
        }
        // Si todavía no está poweredOn, centralManagerDidUpdateState arranca
        // el scan en cuanto llegue ese estado.
    }

    private func startScanning() {
        centralManager.scanForPeripherals(
            withServices: nil,
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            startScanning()
        case .unauthorized, .unsupported:
            onError?("Bluetooth no disponible para escanear (estado \(central.state.rawValue))")
        default:
            break
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        if let manufacturerData = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data {
            onManufacturerData?(manufacturerData, peripheral)
            return
        }
        let advertisedServices = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []
        guard advertisedServices.contains(BeaconGattService.serviceUUID),
              !connectingPeripherals.contains(peripheral) else { return }
        connectingPeripherals.insert(peripheral)
        peripheral.delegate = self
        centralManager.connect(peripheral)
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.connectionTimeout) { [weak self] in
            self?.abandonIfStillConnecting(peripheral)
        }
    }

    private func abandonIfStillConnecting(_ peripheral: CBPeripheral) {
        guard connectingPeripherals.contains(peripheral) else { return }
        centralManager.cancelPeripheralConnection(peripheral)
        connectingPeripherals.remove(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([BeaconGattService.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        connectingPeripherals.remove(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        connectingPeripherals.remove(peripheral)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == BeaconGattService.serviceUUID }) else {
            onError?("Peer anunciaba el servicio de Farosos pero no lo expuso al conectarse: \(error?.localizedDescription ?? "servicio no encontrado")")
            centralManager.cancelPeripheralConnection(peripheral)
            return
        }
        peripheral.discoverCharacteristics([BeaconGattService.characteristicUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let characteristic = service.characteristics?.first(where: { $0.uuid == BeaconGattService.characteristicUUID }) else {
            onError?("Peer sin la característica de beacon esperada: \(error?.localizedDescription ?? "característica no encontrada")")
            centralManager.cancelPeripheralConnection(peripheral)
            return
        }
        peripheral.readValue(for: characteristic)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        defer { centralManager.cancelPeripheralConnection(peripheral) }
        guard error == nil, let data = characteristic.value else { return }
        onGattPacketData?(data, peripheral)
    }
}
