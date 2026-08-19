package com.farosos.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.farosos.directchat.ChatMessage

/**
 * Canal de chat directo (#61) — lista de mensajes + input de texto libre.
 * `isOwnSide` distingue quién es "yo" en esta pantalla (la víctima ve sus
 * propios mensajes a la derecha; el rescatista ve los suyos a la derecha) —
 * un mismo componente sirve para ambos roles, solo cambia qué lado de
 * `ChatMessage.fromVictim` se considera "propio".
 */
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    isOwnSide: (ChatMessage) -> Boolean,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    connectionStatus: String? = null
) {
    var draft by remember { mutableStateOf("") }

    Column(modifier = modifier.padding(16.dp)) {
        if (connectionStatus != null) {
            Text(connectionStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(messages) { message ->
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(
                        text = if (isOwnSide(message)) "Yo" else "Otro",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(text = message.text)
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Escribí un mensaje") }
            )
            Button(
                onClick = {
                    if (draft.isNotBlank()) {
                        onSend(draft)
                        draft = ""
                    }
                }
            ) { Text("Enviar") }
        }
    }
}
