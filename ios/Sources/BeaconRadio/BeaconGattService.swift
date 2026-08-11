import CoreBluetooth

/// Identificadores del servicio/característica GATT que usa **solo el lado
/// que emite en iOS** para publicar su `BeaconPacket` completo de 26 bytes.
///
/// Revisión de la decisión de arquitectura 2 (`spec/packet-format.md`),
/// hecha durante esta ticket (#6): `CBPeripheralManager.startAdvertising`
/// en iOS únicamente admite `CBAdvertisementDataLocalNameKey` y
/// `CBAdvertisementDataServiceUUIDsKey` en el rol periférico — Manufacturer
/// Specific Data no es una clave soportada para anunciar (confirmado en la
/// documentación de Apple y en una respuesta de un ingeniero de Apple DTS
/// en su foro de desarrolladores), a diferencia del rol central, que sí
/// puede leerla al escanear. Android no tiene esta restricción y sigue
/// emitiendo por advertising legacy puro con `ManufacturerDataFrame`.
///
/// El advertisement de iOS solo señaliza "soy un nodo Farosos" (este
/// Service UUID + un Local Name); el payload real de 26 bytes viaja sin
/// envoltorio adicional como el valor de esta característica, de solo
/// lectura, que un central lee al conectarse. El formato de wire de 26
/// bytes en sí no cambia — sigue siendo el mismo `BeaconPacketCodec`
/// compartido con Android.
public enum BeaconGattService {
    public static let serviceUUID = CBUUID(string: "6F415A2E-FA4C-4A2A-9A1B-2E9E6B5B9A10")
    public static let characteristicUUID = CBUUID(string: "6F415A2E-FA4C-4A2A-9A1B-2E9E6B5B9A11")
}
