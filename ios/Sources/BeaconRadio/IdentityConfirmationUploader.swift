import Foundation

/// Sube el `VerifiedIdentityRegistry` (Caso A) al backend de agregación
/// mientras el teléfono esté en `GATEWAY_ACTIVO` (ticket #52) — mismo
/// molde que `GatewayUploader`: sube el snapshot completo ya conocido al
/// arrancar y se suscribe a identidades nuevas mientras está activo.
public final class IdentityConfirmationUploader {
    public var onError: ((Error) -> Void)?

    private let registry: VerifiedIdentityRegistry
    private let uploader: IdentityConfirmationUploading

    public init(registry: VerifiedIdentityRegistry, uploader: IdentityConfirmationUploading) {
        self.registry = registry
        self.uploader = uploader
    }

    /// Sube el snapshot completo ya conocido y se suscribe a identidades
    /// nuevas — mismo camino de código (`upload`) para ambos casos.
    public func start() {
        registry.onIdentityRecorded = { [weak self] deviceIdHash in
            self?.upload(deviceIdHash)
        }
        for deviceIdHash in registry.allDeviceIdHashes() {
            upload(deviceIdHash)
        }
    }

    /// Dejar de escuchar identidades nuevas — no vuelve a subir nada hasta
    /// el próximo `start()`.
    public func stop() {
        registry.onIdentityRecorded = nil
    }

    private func upload(_ deviceIdHash: Data) {
        uploader.upload(deviceIdHash: deviceIdHash) { [weak self] result in
            if case .failure(let error) = result {
                self?.onError?(error)
            }
        }
    }
}
