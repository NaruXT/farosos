import CoreBluetooth

/// Identificadores del servicio/características GATT del canal de chat
/// directo (#61/#62) — servicio nuevo, no reutiliza `BeaconGattService`
/// (que es de solo lectura, para el propio beacon). Vive en `BeaconRadio`
/// junto a `BeaconGattService` porque ambos son solo identificadores, sin
/// dependencias de `CoreBluetooth` más allá de `CBUUID` — la lógica real
/// (`CBPeripheralManager`/`CBCentralManager`) vive en la capa de app,
/// igual que `BeaconGattService`.
///
/// Tres características: `hostPublicKeyCharacteristic` (lectura, la clave
/// pública X25519 efímera de la víctima para esta conexión),
/// `guestPublicKeyCharacteristic` (escritura, el rescatista manda la suya),
/// `messageCharacteristic` (escritura + notificación, mensajes cifrados en
/// ambos sentidos — ver `DirectChat.ChatHostSession`/`ChatClientSession`
/// para el protocolo exacto sobre estos tres).
public enum ChatGattService {
    public static let serviceUUID = CBUUID(string: "6F415A2E-FA4C-4A2A-9A1B-2E9E6B5B9A20")
    public static let hostPublicKeyCharacteristicUUID = CBUUID(string: "6F415A2E-FA4C-4A2A-9A1B-2E9E6B5B9A21")
    public static let guestPublicKeyCharacteristicUUID = CBUUID(string: "6F415A2E-FA4C-4A2A-9A1B-2E9E6B5B9A22")
    public static let messageCharacteristicUUID = CBUUID(string: "6F415A2E-FA4C-4A2A-9A1B-2E9E6B5B9A23")
}
