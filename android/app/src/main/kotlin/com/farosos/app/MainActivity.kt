package com.farosos.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Permisos runtime necesarios para advertising/scanning/GATT BLE. Android
 * 12+ (API 31+) usa los permisos dedicados de "nearby devices" — incluye
 * `BLUETOOTH_CONNECT`, necesario para conectarse por GATT a peers iOS
 * (ticket #11), no solo para escanear/anunciar; antes de eso, el sistema
 * exige ubicación para poder escanear (aunque no la usemos — la
 * latitud/longitud del beacon viaja en el payload, no se deriva del scan).
 */
private val bluetoothPermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var isRegistered by remember {
                        mutableStateOf(ParticipantStore.hasRegisteredProfile(applicationContext))
                    }
                    if (!isRegistered) {
                        RegistrationScreen(onCompleted = { isRegistered = true })
                    } else {
                        val viewModel: EmergencyViewModel = viewModel()
                        val permissionLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestMultiplePermissions()
                        ) { grantResults ->
                            if (grantResults.values.all { it }) viewModel.startRadioIfNotStarted()
                        }
                        LaunchedEffect(Unit) { permissionLauncher.launch(bluetoothPermissions) }

                        // Operación foreground-only: Android no detiene BLE por su
                        // cuenta al pasar a background (a diferencia de iOS).
                        val lifecycleOwner = LocalLifecycleOwner.current
                        DisposableEffect(lifecycleOwner, viewModel) {
                            val observer = LifecycleEventObserver { _, event ->
                                when (event) {
                                    Lifecycle.Event.ON_START -> viewModel.onAppForegrounded()
                                    Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                                    else -> Unit
                                }
                            }
                            lifecycleOwner.lifecycle.addObserver(observer)
                            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                        }

                        EmergencyScreen(viewModel)
                    }
                }
            }
        }
    }
}
