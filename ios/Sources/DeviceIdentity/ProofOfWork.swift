import CryptoKit
import Foundation

/// Mitigación Sybil de Caso A — hashcash clásico sobre `deviceIdHash`: busca
/// un nonce de 8 bytes tal que `SHA-256(deviceIdHash || nonce)` tenga al
/// menos `difficultyBits` ceros a la izquierda. Se calcula una única vez al
/// instalar (decisión previa a esta ticket, 2026-08-18): `difficultyBits =
/// 20` da ~1s de cómputo en un teléfono moderno y hasta ~10s en gama baja —
/// suficiente para encarecer fabricar identidades falsas en lote sin
/// castigar la instalación legítima. No defiende contra un atacante con
/// GPU/hardware dedicado (mismo tipo de límite aceptado que el resto de la
/// autenticación de Caso A, ver `spec/packet-format.md`).
public enum ProofOfWork {
    public static let difficultyBits = 20

    public static func solve(deviceIdHash: Data, difficultyBits: Int = ProofOfWork.difficultyBits) -> Data {
        var counter: UInt64 = 0
        while true {
            let nonce = nonceData(for: counter)
            if leadingZeroBits(of: hash(deviceIdHash: deviceIdHash, nonce: nonce)) >= difficultyBits {
                return nonce
            }
            counter += 1
        }
    }

    public static func isValid(deviceIdHash: Data, nonce: Data, difficultyBits: Int = ProofOfWork.difficultyBits) -> Bool {
        leadingZeroBits(of: hash(deviceIdHash: deviceIdHash, nonce: nonce)) >= difficultyBits
    }

    private static func hash(deviceIdHash: Data, nonce: Data) -> Data {
        Data(SHA256.hash(data: deviceIdHash + nonce))
    }

    private static func nonceData(for counter: UInt64) -> Data {
        withUnsafeBytes(of: counter.bigEndian) { Data($0) }
    }

    static func leadingZeroBits(of digest: Data) -> Int {
        var count = 0
        for byte in digest {
            if byte == 0 {
                count += 8
                continue
            }
            count += byte.leadingZeroBitCount
            break
        }
        return count
    }
}
