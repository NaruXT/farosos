import Foundation

/// Desacopla la subida del perfil de `participants/{device_id_hash}` de
/// `GATEWAY_ACTIVO` (ADR-0003): se dispara con cualquier señal de
/// conectividad real, sin importar el rol de red del teléfono, porque el
/// registro ocurre antes de cualquier emergencia. Reintenta en la siguiente
/// señal de conectividad si la subida falla, o si no había conectividad al
/// momento del registro.
///
/// El perfil pendiente entra únicamente por el inicializador (recién
/// guardado en Keychain por `RegistrationViewModel`, o releído por
/// `KeychainParticipantStore.pendingProfile()` en el siguiente arranque si
/// quedó sin subir) — no hay un setter aparte, para no tener dos caminos
/// distintos hacia el mismo estado.
public final class ParticipantUploadCoordinator {
    public var onUploadSucceeded: (() -> Void)?

    private let deviceIdHash: Data
    private let uploader: ParticipantUploading
    private var pendingProfile: ParticipantProfile?
    private var isUploading = false

    public init(deviceIdHash: Data, uploader: ParticipantUploading, pendingProfile: ParticipantProfile? = nil) {
        self.deviceIdHash = deviceIdHash
        self.uploader = uploader
        self.pendingProfile = pendingProfile
    }

    public func connectivityDetected() {
        guard let profile = pendingProfile, !isUploading else { return }
        isUploading = true
        uploader.upload(deviceIdHash: deviceIdHash, profile: profile) { [weak self] result in
            guard let self else { return }
            self.isUploading = false
            switch result {
            case .success:
                self.pendingProfile = nil
                self.onUploadSucceeded?()
            case .failure:
                break // sigue pendiente, se reintenta en la próxima señal de conectividad
            }
        }
    }
}
