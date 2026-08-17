package com.farosos.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Pantalla de registro opt-in mostrada solo en la primera apertura
 * (ADR-0003) — pide nombre (obligatorio) y contacto (opcional), y nunca
 * bloquea "continuar" por falta de conectividad.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegistrationScreen(onCompleted: () -> Unit, viewModel: RegistrationViewModel = viewModel()) {
    Scaffold(topBar = { TopAppBar(title = { Text("Farosos") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Antes de empezar", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                "Tu nombre ayuda a que el equipo de rescate sepa quién eres si tu teléfono llega a formar parte de la malla.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = viewModel.name,
                onValueChange = { viewModel.name = it },
                label = { Text("Nombre") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = viewModel.contact,
                onValueChange = { viewModel.contact = it },
                label = { Text("Contacto (opcional)") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    viewModel.completeRegistration()
                    onCompleted()
                },
                enabled = viewModel.canContinue
            ) { Text("Continuar") }
        }
    }
}
