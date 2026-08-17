import Foundation

/// Sube el `MeshStateRegistry` al backend de agregación mientras el teléfono
/// esté en `GATEWAY_ACTIVO` (ticket #31, ADR-0002). Clase concreta, sin
/// protocolo propio — mismo molde que `BatteryMonitor`/`ConnectivityMonitor`
/// (closure de error + `start()`/`stop()`). El "primitivo nativo" (Firebase
/// real) queda detrás de `MeshStateUploading`, con la implementación real
/// inyectada desde la capa de app — así los tests mockean ese primitivo en
/// vez de sustituir esta clase.
public final class GatewayUploader {
    public var onError: ((Error) -> Void)?

    private let registry: MeshStateRegistry
    private let uploader: MeshStateUploading

    public init(registry: MeshStateRegistry, uploader: MeshStateUploading) {
        self.registry = registry
        self.uploader = uploader
    }

    /// Sube el snapshot completo ya conocido y se suscribe a actualizaciones
    /// incrementales — mismo camino de código (`upload`) para ambos casos.
    public func start() {
        registry.onStateUpdated = { [weak self] state in
            self?.upload(state)
        }
        for state in registry.allStates() {
            upload(state)
        }
    }

    /// Dejar de escuchar actualizaciones — no vuelve a subir nada hasta el
    /// próximo `start()`.
    public func stop() {
        registry.onStateUpdated = nil
    }

    private func upload(_ state: MeshParticipantState) {
        uploader.upsert(state) { [weak self] result in
            if case .failure(let error) = result {
                self?.onError?(error)
            }
        }
    }
}
