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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log en pantalla de las transiciones de la Máquina de estados A. Este
 * ticket no tiene BLE todavía — cuando llegue (#6/#7), los eventos de red
 * (emitido/recibido/descartado) se agregan a esta misma lista.
 */
@Composable
fun LogScreen(entries: List<LogEntry>, modifier: Modifier = Modifier) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    LazyColumn(modifier = modifier.padding(16.dp)) {
        items(entries.asReversed()) { entry ->
            Row(
                modifier = Modifier.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = entry.state.name, fontWeight = FontWeight.Bold)
                    Text(text = "Secuencia ${entry.sequence}", style = MaterialTheme.typography.bodySmall)
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
