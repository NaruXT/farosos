import XCTest

/// Utilidades compartidas para leer `spec/test-vectors.json` desde los
/// tests de `PacketCodecTests` (layout legado, Caso B, autenticación) —
/// evita repetir el mismo helper de resolución de ruta + parseo de hex en
/// cada archivo de vectores.
enum TestVectorFile {
    static func load() throws -> [String: Any] {
        let vectorsURL = repoRootURL()
            .appendingPathComponent("spec")
            .appendingPathComponent("test-vectors.json")
        let data = try Data(contentsOf: vectorsURL)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    static func hexToData(_ hex: String) -> Data {
        var data = Data(capacity: hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            data.append(UInt8(hex[index..<next], radix: 16)!)
            index = next
        }
        return data
    }

    static func hexByte(_ hex: String) -> UInt8 {
        let digits = hex.hasPrefix("0x") ? String(hex.dropFirst(2)) : hex
        return UInt8(digits, radix: 16)!
    }

    private static func repoRootURL() -> URL {
        // #filePath = .../ios/Tests/PacketCodecTests/TestVectorFile.swift
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent() // quita TestVectorFile.swift -> PacketCodecTests/
            .deletingLastPathComponent() // -> Tests/
            .deletingLastPathComponent() // -> ios/
            .deletingLastPathComponent() // -> raíz del repo
    }
}
