package com.farosos.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.farosos.beaconradio.BEACON_COMPANY_ID
import com.farosos.beaconradio.BeaconGattService
import com.farosos.beaconradio.DirectChatGattService

/**
 * Envoltorio de `BluetoothLeScanner` para escanear advertisements BLE
 * cercanos. Dos rutas de recepción (la segunda, ticket #11):
 *
 * - **Manufacturer Specific Data directa**: peers (p. ej. Android) que sí
 *   pueden anunciar Manufacturer Data como periférico — se decodifica de
 *   inmediato desde el propio advertisement, sin conexión.
 * - **GATT**: peers iOS, que solo pueden señalizar su presencia con
 *   `BeaconGattService.SERVICE_UUID` (ver ese tipo para el porqué) — hay
 *   que conectarse, descubrir el servicio/característica y leerla para
 *   obtener el `BeaconPacket` real.
 *
 * Una conexión GATT es mucho más cara (batería, y el chip BLE de Android
 * solo admite un puñado de conexiones simultáneas) que leer un
 * advertisement pasivo. [maxConcurrentGattConnections] limita cuántas se
 * abren a la vez, y [retryCooldownMillis] evita reintentar la misma
 * dirección mientras la conexión anterior sigue "fresca" — un peer iOS
 * sigue anunciando el mismo Service UUID muchas veces por segundo, y sin
 * este cooldown cada una de esas repeticiones dispararía una conexión
 * nueva. [connectionTimeoutMillis] abandona una conexión que nunca termina
 * de conectar/leer (p. ej. el peer se aleja a medio camino) — mismo patrón
 * que el `connectionTimeout` de 5s de `BleScanner.swift` en iOS.
 */
class BleScanner(
    private val context: Context,
    private val maxConcurrentGattConnections: Int = 3,
    private val retryCooldownMillis: Long = 5_000,
    private val connectionTimeoutMillis: Long = 5_000
) {
    /**
     * Ambos traen el `BluetoothDevice` junto al payload — no solo para
     * decodificar el `BeaconPacket`, sino porque es la única forma de saber
     * a qué dispositivo real corresponde un `device_id_hash` dado. Esa
     * asociación es lo que necesita el chat directo (#61/#63) para resolver
     * a quién conectarse: `onChatHostDiscovered` (abajo) solo cubre el caso
     * Android↔Android (Manufacturer Data con el Service UUID del chat, algo
     * que iOS no puede anunciar — decisión 13 de `spec/packet-format.md`),
     * así que una víctima iOS nunca dispara ese callback. Estas dos rutas,
     * en cambio, ya cubren a cualquier peer (Android por Manufacturer Data
     * directa, iOS por GATT) como efecto secundario del escaneo normal de
     * la malla — es la fuente real que resuelve el caso iOS.
     */
    var onManufacturerData: ((ByteArray, BluetoothDevice) -> Unit)? = null
    var onGattPacketData: ((ByteArray, BluetoothDevice) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * Atajo específico para Android↔Android: un peer cercano anuncia el
     * servicio de chat directo (#61/#63) con el propio `device_id_hash`
     * como Manufacturer Data junto al Service UUID del chat
     * (`ChatGattServer.start`). No cubre víctimas iOS (no pueden anunciar
     * Manufacturer Data) — para eso, `onManufacturerData`/`onGattPacketData`
     * de arriba son la fuente que sí funciona en ambas plataformas. No
     * dispara una conexión GATT por sí solo — a diferencia del beacon de
     * iOS, el chat solo se conecta cuando el usuario elige "Abrir chat"
     * sobre un caso específico.
     */
    var onChatHostDiscovered: ((deviceIdHash: ByteArray, device: BluetoothDevice) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())

    /** Dirección MAC -> conexión GATT en curso, para poder cerrarla al terminar o al hacer timeout. */
    private val activeConnections = mutableMapOf<String, BluetoothGatt>()

    /** Dirección MAC -> próximo instante en el que se permite un nuevo intento de conexión. */
    private val nextAllowedConnectAt = mutableMapOf<String, Long>()

    private val leScanner: BluetoothLeScanner?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.bluetoothLeScanner

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
            val manufacturerData = result.scanRecord?.getManufacturerSpecificData(BEACON_COMPANY_ID)

            if (DirectChatGattService.SERVICE_UUID in serviceUuids) {
                // El anuncio del chat reusa el mismo Company ID para llevar
                // el device_id_hash (6 bytes) — no es un beacon, se
                // distingue por traer el Service UUID del chat presente.
                if (manufacturerData != null) onChatHostDiscovered?.invoke(manufacturerData, result.device)
                return
            }
            if (manufacturerData != null) {
                onManufacturerData?.invoke(manufacturerData, result.device)
                return
            }
            val advertisesBeaconService = BeaconGattService.SERVICE_UUID in serviceUuids
            if (advertisesBeaconService) connectForGattRead(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            onError?.invoke("Error al escanear (código $errorCode)")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finishConnection(gatt)
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> discoverServices(gatt)
                BluetoothProfile.STATE_DISCONNECTED -> finishConnection(gatt)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = gatt.getService(BeaconGattService.SERVICE_UUID)
                ?.getCharacteristic(BeaconGattService.CHARACTERISTIC_UUID)
            if (characteristic == null) {
                onError?.invoke("Peer anunciaba el servicio de Farosos pero no lo expuso al conectarse")
                finishConnection(gatt)
                return
            }
            readCharacteristic(gatt, characteristic)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") // minSdk 26 no tiene la variante de 4 parámetros, agregada en API 33
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                characteristic.value?.let { onGattPacketData?.invoke(it, gatt.device) }
            }
            finishConnection(gatt)
        }
    }

    @SuppressLint("MissingPermission") // el caller garantiza BLUETOOTH_SCAN antes de llamar
    fun start() {
        val scanner = leScanner ?: run {
            onError?.invoke("Este dispositivo no tiene BluetoothLeScanner disponible")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(emptyList(), settings, callback)
    }

    /** Detiene el scan y cualquier conexión GATT en curso — se llama al pasar a background. */
    @SuppressLint("MissingPermission")
    fun stop() {
        leScanner?.stopScan(callback)
        activeConnections.values.toList().forEach { it.disconnect(); it.close() }
        activeConnections.clear()
        nextAllowedConnectAt.clear()
    }

    /**
     * Hallazgo de campo (#64): `stopScan()` no cierra la ventana de carrera
     * a tiempo para el chat directo - un `onScanResult` que ya estaba en la
     * cola del hilo principal justo antes de llamar a [stop] igual dispara
     * [connectForGattRead] después, superponiendo una conexión extra del
     * escáner con la que el chat recién abrió al mismo peer. Eso confundió
     * el lado de la víctima lo suficiente como para que cortara la conexión
     * real del chat a los ~33s (`status=19`, GATT_CONN_TERMINATE_PEER_USER).
     * Este flag se chequea sincrónicamente al principio de
     * [connectForGattRead], en el mismo hilo donde ya corre `onScanResult`
     * - cierra la ventana de carrera que `stopScan()` deja abierta.
     */
    private var gattConnectsPaused = false

    fun pauseGattConnects() {
        gattConnectsPaused = true
    }

    fun resumeGattConnects() {
        gattConnectsPaused = false
    }

    // El overload de 4 parámetros está deprecado a favor de uno basado en
    // `BluetoothGattConnectionSettings` — pero esa API es nueva y no existe
    // en versiones de Android anteriores a la más reciente, mientras que
    // `minSdk` de este proyecto es 26. El overload deprecado sigue siendo
    // el compatible con el hardware real de prueba (Redmi A3, Samsung A10).
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission") // el caller garantiza BLUETOOTH_CONNECT antes de llamar
    private fun connectForGattRead(device: BluetoothDevice) {
        android.util.Log.d("FarososDiag", "scanner: connectForGattRead device=${device.address} paused=$gattConnectsPaused")
        if (gattConnectsPaused) return
        val now = SystemClock.elapsedRealtime()
        val nextAllowed = nextAllowedConnectAt[device.address]
        if (nextAllowed != null && now < nextAllowed) return
        if (activeConnections.size >= maxConcurrentGattConnections) return

        nextAllowedConnectAt[device.address] = now + retryCooldownMillis
        val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        activeConnections[device.address] = gatt
        handler.postDelayed({ abandonIfStillConnecting(device.address) }, connectionTimeoutMillis)
    }

    @SuppressLint("MissingPermission")
    private fun discoverServices(gatt: BluetoothGatt) {
        gatt.discoverServices()
    }

    @SuppressLint("MissingPermission")
    private fun readCharacteristic(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.readCharacteristic(characteristic)
    }

    @SuppressLint("MissingPermission")
    private fun abandonIfStillConnecting(address: String) {
        val gatt = activeConnections[address] ?: return
        finishConnection(gatt)
    }

    /**
     * Punto único de cierre — se llama tanto en el camino feliz (ya se leyó
     * la característica) como en cualquier falla (conexión rechazada,
     * servicio/característica ausente, timeout). Idempotente: si ya se
     * había cerrado esta dirección, no vuelve a tocar el `BluetoothGatt`.
     */
    @SuppressLint("MissingPermission")
    private fun finishConnection(gatt: BluetoothGatt) {
        val address = gatt.device.address
        if (activeConnections.remove(address) == null) return
        gatt.disconnect()
        gatt.close()
    }
}
