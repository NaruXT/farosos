import BeaconRadio
import CaseResolution
import Foundation
import PacketCodec

/// Primera pantalla del proyecto que muestra el estado de *otros*
/// participantes, no solo el propio (#55/#57). Fuente: `MeshStateRegistry`
/// ya existente — snapshot puntual vía `refresh()`, sin suscripción en vivo,
/// porque `onStateUpdated` ya está tomado en exclusiva por `GatewayUploader`
/// mientras el teléfono está en `GATEWAY_ACTIVO` (#31); competir por ese
/// único slot de closure rompería la subida real de la malla. Refrescar al
/// abrir la pantalla es suficiente para el alcance de esta ticket.
@MainActor
final class KnownCasesViewModel: ObservableObject {
    @Published private(set) var cases: [MeshParticipantState] = []

    private let ownDeviceIdHash: Data
    private let meshStateRegistry: MeshStateRegistry
    private let coordinator: ResolutionUploadCoordinator

    init(ownDeviceIdHash: Data, meshStateRegistry: MeshStateRegistry, coordinator: ResolutionUploadCoordinator) {
        self.ownDeviceIdHash = ownDeviceIdHash
        self.meshStateRegistry = meshStateRegistry
        self.coordinator = coordinator
    }

    /// Excluye siempre el propio caso (AC de #57) y cualquier estado que no
    /// sea `AYUDA`/`SILENCIO_TIMEOUT` — mismos dos únicos estados a los que
    /// aplica "resuelto"/"atendiendo" (#55).
    func refresh() {
        cases = meshStateRegistry.allStates().filter { state in
            state.deviceIdHash != ownDeviceIdHash && (state.status == .ayuda || state.status == .silencioTimeout)
        }
    }

    /// Solo guarda localmente y encola — la subida real la dispara
    /// `ConnectivityMonitor` dentro de `EmergencyViewModel` (mismo criterio
    /// que `RegistrationViewModel.completeRegistration()`, ADR-0003: "para
    /// que 'continuar' nunca requiera conectividad"), no un intento
    /// disparado a mano en el momento de marcar.
    func markAttending(_ caseState: MeshParticipantState) {
        let mark = AttendingMark(
            victimDeviceIdHash: caseState.deviceIdHash,
            victimSequence: caseState.sequence,
            resolverDeviceIdHash: ownDeviceIdHash,
            markedAt: UInt32(Date().timeIntervalSince1970)
        )
        KeychainResolutionStore.appendPendingAttending(mark)
        coordinator.markAttending(mark)
    }

    /// Limitación conocida ya documentada en `LocalBeaconFactory.makeBeacon`:
    /// sin captura de GPS todavía en ningún punto del proyecto — lat/lon
    /// quedan en 0 hasta que un ticket futuro integre ubicación real. La
    /// verificación de proximidad server-side (#59) trata esto igual que
    /// "sin ubicación disponible", nunca oculta el caso (#55).
    func markResolved(_ caseState: MeshParticipantState) {
        let mark = ResolvedMark(
            victimDeviceIdHash: caseState.deviceIdHash,
            victimSequence: caseState.sequence,
            resolverDeviceIdHash: ownDeviceIdHash,
            resolverLatitudeE7: 0,
            resolverLongitudeE7: 0,
            markedAt: UInt32(Date().timeIntervalSince1970)
        )
        KeychainResolutionStore.appendPendingResolved(mark)
        coordinator.markResolved(mark)
    }
}
