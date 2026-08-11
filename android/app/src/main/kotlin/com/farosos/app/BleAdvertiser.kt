package com.farosos.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import com.farosos.beaconradio.BEACON_COMPANY_ID

/**
 * Envoltorio de `BluetoothLeAdvertiser` para difundir el beacon actual del
 * nodo. A diferencia de iOS, Android sí permite anunciar Manufacturer
 * Specific Data directamente como periférico — sin GATT — así que no hace
 * falta un tipo de envoltorio propio: `addManufacturerData` ya separa el
 * Company ID del payload por su cuenta.
 */
class BleAdvertiser(private val context: Context) {
    var onError: ((String) -> Unit)? = null

    private val leAdvertiser: BluetoothLeAdvertiser?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.bluetoothLeAdvertiser

    private var currentCallback: AdvertiseCallback? = null

    /** Reemplaza el payload que este nodo difunde. */
    @SuppressLint("MissingPermission") // el caller garantiza BLUETOOTH_ADVERTISE antes de llamar
    fun updateAdvertisedData(packetBytes: ByteArray) {
        val advertiser = leAdvertiser ?: run {
            onError?.invoke("Este dispositivo no tiene BluetoothLeAdvertiser disponible")
            return
        }
        stopAdvertising(advertiser)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(BEACON_COMPANY_ID, packetBytes)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                onError?.invoke("Error al iniciar advertising (código $errorCode)")
            }
        }
        currentCallback = callback
        advertiser.startAdvertising(settings, data, callback)
    }

    /** Detiene el advertising — se llama al pasar a background (operación foreground-only). */
    fun stop() {
        leAdvertiser?.let { stopAdvertising(it) }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising(advertiser: BluetoothLeAdvertiser) {
        currentCallback?.let { advertiser.stopAdvertising(it) }
        currentCallback = null
    }
}
