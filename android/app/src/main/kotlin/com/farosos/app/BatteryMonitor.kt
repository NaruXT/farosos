package com.farosos.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat

/**
 * Envoltorio de `ACTION_BATTERY_CHANGED` (sticky broadcast), sin
 * interfaz — mismo molde que `BleAdvertiser`/`BleScanner`. Push-based
 * (ticket #17, decisión de diseño): un `BroadcastReceiver` registrado,
 * sin polling. Al registrarse, el sistema entrega de inmediato el valor
 * sticky actual, así que no hace falta una lectura inicial aparte.
 */
class BatteryMonitor(private val context: Context) {
    data class Reading(val percent: Int, val isCharging: Boolean)

    var onBatteryChanged: ((Reading) -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            // `BATTERY_STATUS_FULL` implica conectado a corriente (topado, ya
            // no drena) — cuenta como "cargando" para la recuperación de
            // `BAJO_CONSUMO`, igual que `BATTERY_STATUS_CHARGING`.
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            onBatteryChanged?.invoke(Reading(percent = level * 100 / scale, isCharging = isCharging))
        }
    }

    fun start() {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Desregistra el receiver — se llama al destruirse el `ViewModel`. */
    fun stop() {
        context.unregisterReceiver(receiver)
    }
}
