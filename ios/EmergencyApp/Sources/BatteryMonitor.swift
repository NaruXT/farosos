import Foundation
import UIKit

/// Envoltorio de las notificaciones nativas de batería de `UIDevice`, sin
/// protocolo/interfaz — mismo molde que `BleAdvertiser`/`BleScanner`.
/// Push-based (ticket #17, decisión de diseño): se suscribe a las
/// notificaciones del sistema, sin polling.
final class BatteryMonitor {
    struct Reading {
        let percent: Int
        let isCharging: Bool
    }

    var onBatteryChanged: ((Reading) -> Void)?

    private let device: UIDevice
    private var observers: [NSObjectProtocol] = []

    init(device: UIDevice = .current) {
        self.device = device
    }

    func start() {
        device.isBatteryMonitoringEnabled = true
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: UIDevice.batteryLevelDidChangeNotification, object: device, queue: .main) { [weak self] _ in
                self?.notifyCurrent()
            },
            center.addObserver(forName: UIDevice.batteryStateDidChangeNotification, object: device, queue: .main) { [weak self] _ in
                self?.notifyCurrent()
            }
        ]
        notifyCurrent()
    }

    deinit {
        let center = NotificationCenter.default
        observers.forEach(center.removeObserver)
    }

    /// `batteryLevel` es `-1` cuando el monitoreo todavía no arrancó o el
    /// dispositivo no lo soporta — no hay porcentaje real que reportar en
    /// ese caso, así que no se notifica nada en vez de propagar un
    /// porcentaje inventado.
    private func notifyCurrent() {
        guard device.batteryLevel >= 0 else { return }
        let percent = Int((device.batteryLevel * 100).rounded())
        // `.full` implica conectado a corriente (topado, ya no drena) —
        // cuenta como "cargando" para la recuperación de `BAJO_CONSUMO`,
        // igual que `.charging`.
        let isCharging = device.batteryState == .charging || device.batteryState == .full
        onBatteryChanged?(Reading(percent: percent, isCharging: isCharging))
    }
}
