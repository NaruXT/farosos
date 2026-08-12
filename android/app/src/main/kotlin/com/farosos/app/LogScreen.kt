package com.farosos.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.farosos.networkrole.NetworkRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log en pantalla de las transiciones de la Máquina de estados A y de los
 * eventos de red BLE (emitido/recibido/descartado por duplicado, ticket
 * #7), más — desde el ticket #14 — de la Máquina B (rol de red), poblada
 * solo por señales reales del sistema (`BatteryMonitor`/
 * `ConnectivityMonitor`/`RelayQueue`, tickets #21/#25). Mecanismo principal
 * de verificación en campo.
 */
@Composable
fun LogScreen(
    entries: List<LogEntry>,
    networkRole: NetworkRole,
    modifier: Modifier = Modifier
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    LazyColumn(modifier = modifier.padding(16.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Red (Máquina B) — ${networkRole.name}", fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
        }
        items(entries.asReversed()) { entry ->
            Row(
                modifier = Modifier.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title(entry.kind), fontWeight = FontWeight.Bold)
                    Text(text = detail(entry.kind), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    text = timeFormatter.format(Date(entry.timestampMillis)),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            HorizontalDivider()
        }
    }
}

private fun title(kind: LogEntry.Kind): String = when (kind) {
    is LogEntry.Kind.Transition -> kind.state.name
    is LogEntry.Kind.BeaconReceived -> "RECIBIDO"
    is LogEntry.Kind.DuplicateDiscarded -> "DESCARTADO POR DUPLICADO"
    is LogEntry.Kind.TtlExhausted -> "DESCARTADO POR TTL AGOTADO"
    is LogEntry.Kind.Info -> "INFO"
    is LogEntry.Kind.NetworkRoleTransition -> "RED: ${kind.role.name}"
}

private fun detail(kind: LogEntry.Kind): String = when (kind) {
    is LogEntry.Kind.Transition -> "Secuencia ${kind.sequence}"
    is LogEntry.Kind.BeaconReceived -> "De ${shortHex(kind.deviceIdHash)} · TTL ${kind.ttl} · Secuencia ${kind.sequence}"
    is LogEntry.Kind.DuplicateDiscarded -> "De ${shortHex(kind.deviceIdHash)} · Nonce ${kind.nonce}"
    is LogEntry.Kind.TtlExhausted -> "De ${shortHex(kind.deviceIdHash)} · Secuencia ${kind.sequence}"
    is LogEntry.Kind.Info -> kind.message
    is LogEntry.Kind.NetworkRoleTransition -> "Máquina B (rol de red)"
}

private fun shortHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
