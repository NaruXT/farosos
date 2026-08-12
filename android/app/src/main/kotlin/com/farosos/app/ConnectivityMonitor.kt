package com.farosos.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Envoltorio de `ConnectivityManager.NetworkCallback`, sin interfaz —
 * mismo molde que `BleAdvertiser`/`BleScanner`. Sin ping ni request
 * propio: el filtro exige `NET_CAPABILITY_INTERNET` +
 * `NET_CAPABILITY_VALIDATED`, que el sistema ya resuelve por su cuenta —
 * a diferencia de iOS (`NWPathMonitor`, best-effort), esto sí confirma
 * salida real a internet.
 */
class ConnectivityMonitor(private val context: Context) {
    var onConnectivityChanged: ((Boolean) -> Unit)? = null

    private val connectivityManager
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onConnectivityChanged?.invoke(true)
        }

        override fun onLost(network: Network) {
            onConnectivityChanged?.invoke(false)
        }
    }

    fun start() {
        connectivityManager.registerNetworkCallback(request, callback)
    }

    /** Desregistra el callback — se llama al destruirse el `ViewModel`. */
    fun stop() {
        connectivityManager.unregisterNetworkCallback(callback)
    }
}
