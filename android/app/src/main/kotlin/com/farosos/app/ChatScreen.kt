package com.farosos.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    val listState = rememberLazyListState()

    // Fija la lista en el último mensaje al llegar uno nuevo - mismo
    // criterio en ambas plataformas (hallazgo de campo #64: sin esto, había
    // que hacer scroll manual para ver la conversación al día).
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(modifier = modifier.padding(16.dp)) {
        if (connectionStatus != null) {
            Text(connectionStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(modifier = Modifier.weight(1f), state = listState) {
            items(messages) { message ->
                val isOwn = isOwnSide(message)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .background(
                                if (isOwn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(12.dp)
                            )
                            .padding(10.dp)
                    ) {
                        Text(
                            text = if (isOwn) "Yo" else "Otro",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(text = message.text)
                    }
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
