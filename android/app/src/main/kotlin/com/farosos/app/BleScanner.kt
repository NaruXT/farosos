package com.farosos.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.farosos.beaconradio.BEACON_COMPANY_ID

/**
 * Envoltorio de `BluetoothLeScanner` para escanear advertisements BLE
 * cercanos. `ScanRecord.getManufacturerSpecificData(companyId)` ya devuelve
 * el payload con el Company ID filtrado por el propio sistema — a
 * diferencia de iOS, no hace falta parsear el envoltorio a mano.
 */
class BleScanner(private val context: Context) {
    var onManufacturerData: ((ByteArray) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val leScanner: BluetoothLeScanner?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.bluetoothLeScanner

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getManufacturerSpecificData(BEACON_COMPANY_ID) ?: return
            onManufacturerData?.invoke(data)
        }

        override fun onScanFailed(errorCode: Int) {
            onError?.invoke("Error al escanear (código $errorCode)")
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

    /** Detiene el scan — se llama al pasar a background (operación foreground-only). */
    @SuppressLint("MissingPermission")
    fun stop() {
        leScanner?.stopScan(callback)
    }
}
