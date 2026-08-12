import Foundation
import Network

/// Envoltorio de `NWPathMonitor`, sin protocolo/interfaz — mismo molde que
/// `BleAdvertiser`/`BleScanner`. Sin ping ni request propio: usa
/// exclusivamente la validación pasiva del sistema operativo.
///
/// Best-effort en esta plataforma (ticket #17, asimetría documentada): a
/// diferencia de Android (`NET_CAPABILITY_VALIDATED`, que sí confirma
/// salida real a internet), `path.status == .satisfied` solo confirma que
/// hay una interfaz con ruta activa — un WiFi con portal cautivo puede
/// reportar `.satisfied` sin tener internet real.
final class ConnectivityMonitor {
    var onConnectivityChanged: ((Bool) -> Void)?

    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "com.farosos.connectivitymonitor")

    func start() {
        monitor.pathUpdateHandler = { [weak self] path in
            let hasConnectivity = path.status == .satisfied
            DispatchQueue.main.async {
                self?.onConnectivityChanged?(hasConnectivity)
            }
        }
        monitor.start(queue: queue)
    }
}
