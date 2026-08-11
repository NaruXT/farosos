package com.farosos.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.farosos.beaconradio.DedupCache
import com.farosos.beaconradio.LocalBeaconFactory
import com.farosos.beaconradio.RandomNonceGenerator
import com.farosos.beaconradio.RelayPolicy
import com.farosos.beaconradio.RelayQueue
import com.farosos.codec.BeaconPacketCodec
import com.farosos.personstate.PersonState
import com.farosos.personstate.PersonStateMachine

/**
 * Limitación conocida: el estado vive solo en memoria dentro de esta
 * instancia. La recuperación tardía desde `SILENCIO_TIMEOUT` funciona
 * mientras el proceso siga vivo (probado en el emulador), pero no sobrevive
 * a que el sistema mate el proceso — persistir el estado entre lanzamientos
 * queda fuera de esta ticket; es una decisión de diseño propia (dónde
 * guardarlo, cómo re-armar los timers en curso) que merece su propio issue.
 *
 * Limitación conocida (#7): la interoperabilidad real de las dos radios BLE
 * (advertising + scanning entre dos teléfonos distintos) no se verificó en
 * esta sesión por falta de un segundo dispositivo físico — eso es
 * exactamente lo que cubre la ticket #10 (verificación de campo A→B→C).
 * El radio arranca recién cuando `startRadioIfNotStarted()` confirma que
 * los permisos de Bluetooth ya fueron concedidos (ver `MainActivity`).
 *
 * A diferencia de iOS (donde el propio sistema strippea Manufacturer Data
 * en background, dejando "foreground-only" gratis), Android no detiene
 * advertising/scanning por su cuenta — `MainActivity` reenvía los eventos
 * de ciclo de vida `ON_START`/`ON_STOP` a `onAppForegrounded`/
 * `onAppBackgrounded` para cumplir ese requisito explícitamente.
 */
class EmergencyViewModel @JvmOverloads constructor(
    application: Application,
    private val shakeDuration: Double = 3.0,
    private val confirmationWindow: Double = 20.0
) : AndroidViewModel(application) {
    var state by mutableStateOf(PersonState.DORMIDO)
        private set
    val logEntries = mutableStateListOf<LogEntry>()
    var countdownSecondsRemaining by mutableStateOf<Int?>(null)
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val scheduler = RealScheduler()
    private val machine = PersonStateMachine(scheduler, shakeDuration, confirmationWindow)
    private var countdownDeadlineMillis: Long? = null
    private var countdownRunnable: Runnable? = null

    private val deviceIdHash: ByteArray = DeviceIdentity.deviceIdHash(application)
    private val dedupCache = DedupCache()
    private val nonceGenerator = RandomNonceGenerator()
    private val advertiser = BleAdvertiser(application)
    private val scanner = BleScanner(application)
    private val relayQueue = RelayQueue(scheduler)
    private var radioStarted = false
    private var isForeground = false

    init {
        appendLogEntry(LogEntry.Kind.Transition(machine.state, machine.sequence))
        machine.onTransition = { newState -> handleTransition(newState) }
    }

    fun simulateEarthquake() = machine.simulateEarthquake()
    fun confirmOk() = machine.confirmOk()
    fun requestHelp() = machine.requestHelp()

    /**
     * Arranca advertising + scanning por primera vez. Se llama desde
     * `MainActivity` recién cuando el usuario ya concedió los permisos de
     * Bluetooth requeridos — llamarlo antes lanzaría `SecurityException`
     * en Android 12+.
     */
    fun startRadioIfNotStarted() {
        if (radioStarted) return
        radioStarted = true
        wireRadio()
        onAppForegrounded()
    }

    /** Reanuda advertising + scanning — `MainActivity` lo llama en `ON_START`. */
    fun onAppForegrounded() {
        if (!radioStarted || isForeground) return
        isForeground = true
        scanner.start()
        refreshAdvertisedBeacon()
        relayQueue.start()
    }

    /**
     * Detiene advertising + scanning — `MainActivity` lo llama en `ON_STOP`
     * para cumplir el requisito de operación foreground-only. `relayQueue`
     * también se detiene aquí: si siguiera rotando en background, su
     * callback volvería a llamar `advertiser.updateAdvertisedData` y
     * reanudaría el advertising que este método acaba de apagar.
     */
    fun onAppBackgrounded() {
        if (!isForeground) return
        isForeground = false
        relayQueue.stop()
        scanner.stop()
        advertiser.stop()
    }

    private fun handleTransition(newState: PersonState) {
        state = newState
        appendLogEntry(LogEntry.Kind.Transition(newState, machine.sequence))
        if (isForeground) refreshAdvertisedBeacon()

        when (newState) {
            PersonState.ACTIVO_SIN_CONFIRMAR -> startCountdown(shakeDuration)
            PersonState.ESPERANDO_CONFIRMACION -> startCountdown(confirmationWindow)
            else -> stopCountdown()
        }
    }

    private fun appendLogEntry(kind: LogEntry.Kind) {
        logEntries.add(LogEntry(System.currentTimeMillis(), kind))
    }

    // --- BLE (advertising + scanning + dedup, ticket #7) ---

    private fun wireRadio() {
        advertiser.onError = onMain { message -> appendLogEntry(LogEntry.Kind.Info(message)) }
        scanner.onError = onMain { message -> appendLogEntry(LogEntry.Kind.Info(message)) }
        // Manufacturer Data directa (p. ej. peers Android, que sí pueden
        // anunciarla) y GATT (peers iOS, ticket #11 — ver `BleScanner`)
        // traen los mismos 26 bytes sin envoltorio adicional en ambos
        // casos, así que ambas rutas convergen en el mismo pipeline de
        // dedup + log + relay.
        scanner.onManufacturerData = onMain { data -> handleReceivedPacketData(data) }
        scanner.onGattPacketData = onMain { data -> handleReceivedPacketData(data) }
        // `relayQueue` usa el mismo `Scheduler` (respaldado por el main
        // looper) que la Máquina A, así que este callback ya llega en el
        // hilo principal — no hace falta pasar por `onMain`.
        relayQueue.onCurrentPacketChanged = { packet -> advertiser.updateAdvertisedData(BeaconPacketCodec.encode(packet)) }
    }

    /**
     * Reenvía un callback de `BleAdvertiser`/`BleScanner` (que llega en un
     * hilo de Binder, no necesariamente el principal) de vuelta al hilo
     * principal — Compose solo debe mutar estado ahí.
     */
    private fun <Value> onMain(work: (Value) -> Unit): (Value) -> Unit =
        { value -> handler.post { work(value) } }

    /**
     * Reconstruye el `BeaconPacket` local a partir del estado actual de la
     * Máquina A y lo pone en la cola de relay (ticket #9): el propio beacon
     * siempre está presente ahí, junto a los beacons ajenos pendientes de
     * retransmitir, rotando en round-robin. Se auto-registra en la propia
     * caché de dedup al emitir (decisión 12): si este mismo beacon rebota
     * de un vecino, se descarta como duplicado por el mismo camino que
     * cualquier otro, sin una ruta de código especial para "es mío".
     */
    private fun refreshAdvertisedBeacon() {
        val packet = LocalBeaconFactory.makeBeacon(
            deviceIdHash = deviceIdHash,
            status = machine.status,
            sequence = machine.sequence,
            nowEpochSeconds = System.currentTimeMillis() / 1000,
            nonceGenerator = nonceGenerator
        )
        dedupCache.insertIfAbsent(DedupCache.Key(packet.deviceIdHash, packet.nonce))
        relayQueue.updateOwnBeacon(packet)
    }

    /**
     * Punto de entrada compartido por ambas rutas de recepción (Manufacturer
     * Data directa y GATT) — `BeaconPacketCodec.decode` ya filtra por
     * Magic/Versión — lo que no coincide simplemente no decodifica y se
     * ignora aquí, sin entrada de log.
     */
    private fun handleReceivedPacketData(data: ByteArray) {
        val packet = BeaconPacketCodec.decode(data) ?: return
        val key = DedupCache.Key(packet.deviceIdHash, packet.nonce)
        if (!dedupCache.insertIfAbsent(key)) {
            appendLogEntry(LogEntry.Kind.DuplicateDiscarded(packet.deviceIdHash, packet.nonce))
            return
        }
        appendLogEntry(LogEntry.Kind.BeaconReceived(packet.deviceIdHash, packet.ttl, packet.sequence))
        val relayed = RelayPolicy.decrementedForRelay(packet)
        if (relayed != null) {
            relayQueue.enqueueForeignBeacon(relayed)
        } else {
            appendLogEntry(LogEntry.Kind.TtlExhausted(packet.deviceIdHash, packet.sequence))
        }
    }

    private fun startCountdown(durationSeconds: Double) {
        stopCountdown()
        countdownDeadlineMillis = System.currentTimeMillis() + (durationSeconds * 1000).toLong()
        countdownSecondsRemaining = durationSeconds.toInt()

        val runnable = object : Runnable {
            override fun run() {
                tickCountdown()
                if (countdownRunnable === this) {
                    handler.postDelayed(this, 1000)
                }
            }
        }
        countdownRunnable = runnable
        handler.postDelayed(runnable, 1000)
    }

    private fun tickCountdown() {
        val deadline = countdownDeadlineMillis ?: return
        val remainingMillis = deadline - System.currentTimeMillis()
        val remaining = ((remainingMillis + 999) / 1000).toInt()
        countdownSecondsRemaining = maxOf(remaining, 0)
        if (remaining <= 0) stopCountdown()
    }

    private fun stopCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
        countdownDeadlineMillis = null
        countdownSecondsRemaining = null
    }

    override fun onCleared() {
        stopCountdown()
        onAppBackgrounded()
        super.onCleared()
    }
}
