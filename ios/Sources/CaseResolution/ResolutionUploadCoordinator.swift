import Foundation

/// Desacopla la subida de marcas "resuelto"/"atendiendo" (#55) de
/// `GATEWAY_ACTIVO` — se dispara con cualquier señal de conectividad real
/// del propio resolutor, sin pasar por la malla BLE. Mismo molde que
/// `ParticipantUploadCoordinator`, generalizado a una cola en vez de un
/// único pendiente, porque un mismo teléfono puede marcar varios casos
/// distintos antes de recuperar señal.
///
/// Cada `connectivityDetected()` drena la cola completa mientras las
/// subidas sigan teniendo éxito — "resuelto" siempre antes que "atendiendo"
/// dentro de la misma señal (orden arbitrario pero determinístico, ninguna
/// de las dos depende de la otra). Un fallo detiene el drenado hasta la
/// próxima señal de conectividad, mismo criterio de reintento que
/// `ParticipantUploadCoordinator`.
///
/// A diferencia de `ParticipantUploadCoordinator` (un único pendiente que
/// solo entra por el inicializador, "para no tener dos caminos distintos
/// hacia el mismo estado"), acá sí hace falta un setter (`markResolved`/
/// `markAttending`): el registro no es un flujo de una sola vez al abrir la
/// app, sino algo que puede pasar varias veces mientras la app sigue viva.
/// `KeychainResolutionStore` (capa de app) sigue siendo la única fuente de
/// verdad persistente; esta cola en memoria es su reflejo mientras el
/// proceso vive, igual que `pendingProfile` lo era para un único perfil.
public final class ResolutionUploadCoordinator {
    public var onResolvedUploaded: ((ResolvedMark) -> Void)?
    public var onAttendingUploaded: ((AttendingMark) -> Void)?

    private let uploader: ResolutionUploading
    private var pendingResolved: [ResolvedMark]
    private var pendingAttending: [AttendingMark]
    private var isUploading = false

    public init(uploader: ResolutionUploading, pendingResolved: [ResolvedMark] = [], pendingAttending: [AttendingMark] = []) {
        self.uploader = uploader
        self.pendingResolved = pendingResolved
        self.pendingAttending = pendingAttending
    }

    /// Encola una marca nueva — no la sube todavía, queda pendiente hasta la
    /// próxima señal de conectividad (o la que esté en curso, si ninguna lo
    /// está).
    public func markResolved(_ mark: ResolvedMark) {
        pendingResolved.append(mark)
    }

    public func markAttending(_ mark: AttendingMark) {
        pendingAttending.append(mark)
    }

    public func connectivityDetected() {
        guard !isUploading else { return }
        drainNext()
    }

    private func drainNext() {
        if !pendingResolved.isEmpty {
            attempt(pendingResolved[0], upload: uploader.uploadResolved) { [weak self] mark in
                guard let self else { return }
                self.pendingResolved.removeFirst()
                self.onResolvedUploaded?(mark)
                self.drainNext()
            }
            return
        }
        if !pendingAttending.isEmpty {
            attempt(pendingAttending[0], upload: uploader.uploadAttending) { [weak self] mark in
                guard let self else { return }
                self.pendingAttending.removeFirst()
                self.onAttendingUploaded?(mark)
                self.drainNext()
            }
            return
        }
    }

    /// Un intento de subida, sin importar el tipo de marca — la mutación de
    /// cola (`removeFirst`/callback/`drainNext`) queda a cargo de
    /// `onSuccess`, que sí sabe de cuál cola se trata.
    private func attempt<Mark>(
        _ mark: Mark,
        upload: (Mark, @escaping (Result<Void, Error>) -> Void) -> Void,
        onSuccess: @escaping (Mark) -> Void
    ) {
        isUploading = true
        upload(mark) { [weak self] result in
            guard let self else { return }
            self.isUploading = false
            if case .success = result {
                onSuccess(mark)
            } // .failure: sigue pendiente, se reintenta en la próxima señal de conectividad
        }
    }
}
