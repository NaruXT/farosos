import CryptoKit
import Foundation

/// ECDH + MAC de Caso B (`spec/packet-format.md`, sección "Layout Caso B" y
/// decisión 15). `K_shared` se deriva localmente sin handshake, contra la
/// clave pública X25519 fija del backend (#39) usando la clave privada
/// Ed25519 propia (#40) convertida a X25519 vía el mapa birracional estándar
/// — CryptoKit no expone esta conversión directamente, así que se
/// implementa aquí: `X25519_priv = clamp(SHA-512(seed_Ed25519)[:32])`,
/// verificado byte a byte contra el vector de ECDH de `spec/test-vectors.json`.
public enum CaseBAuthentication {
    /// Clave pública X25519 fija del backend (#39, `spec/test-vectors.json`
    /// clave `ecdh.backend_public_key_x25519_hex`) — constante no-secreta,
    /// embebida en el binario igual que `Company ID`/Service UUID.
    public static let backendPublicKeyX25519: Data = hexToData(
        "78e77e1217a3c67319601127b85dc55fe714a23f11e0d22b25d4188c1255963b"
    )

    /// `K_shared = X25519(clamp(SHA-512(privkey_Ed25519)[:32]), pubkey_X25519_backend)`.
    public static func deriveKShared(
        devicePrivateKeyEd25519Seed: Data,
        backendPublicKeyX25519: Data = backendPublicKeyX25519
    ) -> Data {
        let expanded = SHA512.hash(data: devicePrivateKeyEd25519Seed)
        var scalar = Array(expanded.prefix(32))
        scalar[0] &= 0xF8
        scalar[31] &= 0x7F
        scalar[31] |= 0x40

        // Ambos operandos tienen exactamente 32 bytes por construcción
        // (salida truncada de SHA-512, constante fija) — la única causa de
        // error de estos inicializadores (tamaño incorrecto) no puede darse.
        let devicePrivateKey = try! Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(scalar))
        let backendPublicKey = try! Curve25519.KeyAgreement.PublicKey(rawRepresentation: backendPublicKeyX25519)
        let sharedSecret = try! devicePrivateKey.sharedSecretFromKeyAgreement(with: backendPublicKey)
        return sharedSecret.withUnsafeBytes { Data($0) }
    }

    /// `contenido = DeviceIdHash(6) || TipoEstado(1) || Latitud(4) || Longitud(4) || Timestamp(4) || TTL(1) || Secuencia(1)` — 21 bytes.
    /// No incluye `Magic`, `Versión` ni el propio `MAC`.
    public static func authenticatedContent(from packet: CaseBBeaconPacket) -> Data {
        var data = Data(capacity: 21)
        data.append(packet.deviceIdHash)
        data.append(CaseBBeaconPacketCodec.tipoEstado(messageType: packet.messageType, status: packet.status))
        data.appendLE(UInt32(bitPattern: packet.latitudeE7))
        data.appendLE(UInt32(bitPattern: packet.longitudeE7))
        data.appendLE(packet.timestamp)
        data.append(packet.ttl)
        data.append(packet.sequence)
        return data
    }

    /// `MAC = HMAC-SHA256(K_shared, contenido)[:4]`.
    public static func computeMac(kShared: Data, content: Data) -> Data {
        let key = SymmetricKey(data: kShared)
        let fullMac = HMAC<SHA256>.authenticationCode(for: content, using: key)
        return Data(fullMac.prefix(4))
    }

    private static func hexToData(_ hex: String) -> Data {
        var data = Data(capacity: hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            data.append(UInt8(hex[index..<next], radix: 16)!)
            index = next
        }
        return data
    }
}
