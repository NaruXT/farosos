import CaseResolution
import Foundation

/// Persiste las marcas "resuelto"/"atendiendo" (#55) pendientes de subir en
/// Keychain como JSON, mismo patrón que `KeychainParticipantStore` —
/// generalizado a una lista porque un mismo teléfono puede marcar varios
/// casos distintos antes de recuperar señal. Vive en la capa de app, no en
/// el paquete SPM testeado — Keychain requiere el entorno real de la app.
enum KeychainResolutionStore {
    private static let resolvedAccount = "pendingResolvedMarks"
    private static let attendingAccount = "pendingAttendingMarks"

    static func pendingResolved() -> [ResolvedMark] {
        decode([ResolvedMark].self, account: resolvedAccount) ?? []
    }

    static func pendingAttending() -> [AttendingMark] {
        decode([AttendingMark].self, account: attendingAccount) ?? []
    }

    static func appendPendingResolved(_ mark: ResolvedMark) {
        var marks = pendingResolved()
        marks.append(mark)
        encode(marks, account: resolvedAccount)
    }

    static func appendPendingAttending(_ mark: AttendingMark) {
        var marks = pendingAttending()
        marks.append(mark)
        encode(marks, account: attendingAccount)
    }

    /// Se llama desde `ResolutionUploadCoordinator.onResolvedUploaded` tras
    /// una subida exitosa — saca la marca ya subida de lo pendiente.
    static func removePendingResolved(_ mark: ResolvedMark) {
        var marks = pendingResolved()
        marks.removeAll { $0 == mark }
        encode(marks, account: resolvedAccount)
    }

    static func removePendingAttending(_ mark: AttendingMark) {
        var marks = pendingAttending()
        marks.removeAll { $0 == mark }
        encode(marks, account: attendingAccount)
    }

    private static func decode<T: Decodable>(_ type: T.Type, account: String) -> T? {
        guard let json = KeychainStore.read(account: account), let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    private static func encode<T: Encodable>(_ value: T, account: String) {
        guard let data = try? JSONEncoder().encode(value), let json = String(data: data, encoding: .utf8) else { return }
        KeychainStore.write(json, account: account)
    }
}
