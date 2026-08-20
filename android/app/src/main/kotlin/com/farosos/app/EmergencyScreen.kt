package com.farosos.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.farosos.beaconradio.MeshParticipantState
import com.farosos.personstate.PersonState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyScreen(viewModel: EmergencyViewModel) {
    var showingLog by remember { mutableStateOf(false) }
    var showingCases by remember { mutableStateOf(false) }
    var showingOwnChat by remember { mutableStateOf(false) }
    var rescuerChatCase by remember { mutableStateOf<MeshParticipantState?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Farosos") },
                actions = {
                    TextButton(onClick = { showingCases = true }) { Text("Casos") }
                    // Solo tiene sentido abrir el propio chat mientras el
                    // servicio GATT está activo (#61 — mismo predicado que
                    // gatea `ChatGattServer.start`/`stop`).
                    if (viewModel.state.isRequestingHelp) {
                        TextButton(onClick = { showingOwnChat = true }) { Text("Mi chat") }
                    }
                    TextButton(onClick = { showingLog = true }) { Text("Log") }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            when (viewModel.state) {
                PersonState.DORMIDO ->
                    DormidoScreen(onSimulate = viewModel::simulateEarthquake)
                PersonState.ACTIVO_SIN_CONFIRMAR ->
                    ActivoSinConfirmarScreen(secondsRemaining = viewModel.countdownSecondsRemaining)
                PersonState.ESPERANDO_CONFIRMACION ->
                    EsperandoConfirmacionScreen(
                        secondsRemaining = viewModel.countdownSecondsRemaining,
                        onConfirmOk = viewModel::confirmOk,
                        onRequestHelp = viewModel::requestHelp
                    )
                PersonState.CONFIRMADO_OK -> ConfirmadoOkScreen()
                PersonState.AYUDA_SOLICITADA -> AyudaSolicitadaScreen(onCancel = viewModel::confirmOk)
                PersonState.SILENCIO_TIMEOUT -> SilencioTimeoutScreen(onConfirmOk = viewModel::confirmOk)
            }
        }
    }

    if (showingLog) {
        Dialog(onDismissRequest = { showingLog = false }) {
            Scaffold(topBar = { TopAppBar(title = { Text("Actividad") }) }) { padding ->
                LogScreen(
                    entries = viewModel.logEntries,
                    networkRole = viewModel.networkRole,
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }

    if (showingCases) {
        Dialog(onDismissRequest = { showingCases = false }) {
            Scaffold(topBar = { TopAppBar(title = { Text("Casos conocidos") }) }) { padding ->
                CaseResolutionScreen(
                    knownCases = viewModel.knownCases,
                    ownState = viewModel.state,
                    onMarkAttending = viewModel::markCaseAttending,
                    onMarkResolved = viewModel::markCaseResolved,
                    onOpenChat = { case ->
                        if (viewModel.openChatWith(case)) rescuerChatCase = case
                    },
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }

    if (showingOwnChat) {
        Dialog(onDismissRequest = { showingOwnChat = false }) {
            Scaffold(topBar = { TopAppBar(title = { Text("Mi chat") }) }) { padding ->
                ChatScreen(
                    messages = viewModel.ownChatMessages,
                    isOwnSide = { it.fromVictim },
                    onSend = viewModel::sendOwnChatMessage,
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }

    val openRescuerCase = rescuerChatCase
    if (openRescuerCase != null) {
        Dialog(
            onDismissRequest = {
                viewModel.closeChat()
                rescuerChatCase = null
            }
        ) {
            Scaffold(topBar = { TopAppBar(title = { Text("Chat con ${openRescuerCase.deviceIdHash.shortHex()}") }) }) { padding ->
                ChatScreen(
                    messages = viewModel.rescuerChatMessages,
                    isOwnSide = { !it.fromVictim },
                    onSend = viewModel::sendRescuerChatMessage,
                    connectionStatus = if (viewModel.isRescuerChatConnected) null else "Conectando...",
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

@Composable
private fun DormidoScreen(onSimulate: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Todo tranquilo por ahora.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onSimulate) { Text("SIMULAR TERREMOTO") }
    }
}

@Composable
private fun ActivoSinConfirmarScreen(secondsRemaining: Int?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("SISMO DETECTADO", fontWeight = FontWeight.Bold, fontSize = 24.sp)
        secondsRemaining?.let {
            Text("$it", fontWeight = FontWeight.Bold, fontSize = 40.sp)
        }
        Text("Difundiendo beacon…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EsperandoConfirmacionScreen(
    secondsRemaining: Int?,
    onConfirmOk: () -> Unit,
    onRequestHelp: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        secondsRemaining?.let {
            Text("$it", fontWeight = FontWeight.Bold, fontSize = 48.sp)
        }
        Text("¿Estás bien?", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onConfirmOk,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
            ) { Text("ESTOY BIEN") }
            Button(
                onClick = onRequestHelp,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
            ) { Text("NECESITO AYUDA") }
        }
    }
}

@Composable
private fun ConfirmadoOkScreen() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("✓", fontSize = 48.sp, color = Color(0xFF2E7D32))
        Text("Estás marcado como BIEN", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(
            "Tu estado quedó registrado.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AyudaSolicitadaScreen(onCancel: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("⚠", fontSize = 48.sp, color = Color(0xFFC62828))
        Text("AYUDA SOLICITADA", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color(0xFFC62828))
        Text(
            "Tu solicitud de ayuda quedó registrada.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = onCancel,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
        ) { Text("YA ESTOY BIEN / CANCELAR") }
    }
}

@Composable
private fun SilencioTimeoutScreen(onConfirmOk: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("⏰", fontSize = 48.sp, color = Color(0xFFEF6C00))
        Text("No confirmaste a tiempo", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(
            "Es posible que otros estén buscándote. Puedes confirmar ahora, aunque haya pasado la ventana.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = onConfirmOk,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
        ) { Text("ESTOY BIEN (confirmación tardía)") }
    }
}
