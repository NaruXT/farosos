package com.farosos.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.farosos.beaconradio.MeshParticipantState
import com.farosos.codec.BeaconPacket
import com.farosos.personstate.PersonState

/**
 * Lista los casos `AYUDA_SOLICITADA`/`SILENCIO_TIMEOUT` que este teléfono
 * ya conoce (#55) — primera pantalla del proyecto que muestra el estado de
 * *otros* participantes, no solo el propio. Excluye siempre el propio caso
 * (ya no aparece en `knownCases`, ver `EmergencyViewModel.refreshKnownCases`)
 * y oculta ambas acciones mientras el propio estado esté pidiendo ayuda —
 * quien está pidiendo ayuda no está en posición de atender casos ajenos.
 */
@Composable
fun CaseResolutionScreen(
    knownCases: List<MeshParticipantState>,
    ownState: PersonState,
    onMarkAttending: (MeshParticipantState) -> Unit,
    onMarkResolved: (MeshParticipantState) -> Unit,
    onOpenChat: (MeshParticipantState) -> Unit,
    modifier: Modifier = Modifier
) {
    val canActOnOtherCases = ownState != PersonState.AYUDA_SOLICITADA && ownState != PersonState.SILENCIO_TIMEOUT

    Column(modifier = modifier.padding(16.dp)) {
        if (!canActOnOtherCases) {
            Text(
                "Estás pidiendo ayuda — no podés marcar casos ajenos hasta confirmar tu propio estado.",
                color = MaterialTheme.colorScheme.error
            )
        }
        if (knownCases.isEmpty()) {
            Text(
                "Todavía no se conoce ningún caso activo por la malla.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp)
            )
        } else {
            LazyColumn {
                items(knownCases) { case ->
                    Column(modifier = Modifier.padding(vertical = 12.dp)) {
                        Text(text = case.deviceIdHash.shortHex(), fontWeight = FontWeight.Bold)
                        Text(text = statusLabel(case.status), style = MaterialTheme.typography.bodySmall)
                        Text(text = "Secuencia ${case.sequence}", style = MaterialTheme.typography.bodySmall)
                        if (canActOnOtherCases) {
                            Row(
                                modifier = Modifier.padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(onClick = { onMarkAttending(case) }) { Text("Voy a socorrer") }
                                Button(onClick = { onMarkResolved(case) }) { Text("Marcar como resuelto") }
                                Button(onClick = { onOpenChat(case) }) { Text("Abrir chat") }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun statusLabel(status: BeaconPacket.Status): String = when (status) {
    BeaconPacket.Status.AYUDA -> "AYUDA_SOLICITADA"
    BeaconPacket.Status.SILENCIO_TIMEOUT -> "SILENCIO_TIMEOUT"
    else -> status.name
}
