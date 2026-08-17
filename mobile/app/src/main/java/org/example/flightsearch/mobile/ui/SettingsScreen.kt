package org.example.flightsearch.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentBaseUrl: String,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
) {
    var url by remember { mutableStateOf(currentBaseUrl) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Server") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        ) {
            Text(
                "Address of the flight-search backend running on your PC.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Server address") },
                placeholder = { Text("192.168.1.20:8080") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            Text(
                "Same Wi-Fi: your PC's local IP and port, e.g. 192.168.1.20:8080 " +
                    "(run ipconfig on the PC to find it).\n\n" +
                    "Anywhere over the internet: the Tailscale address of your PC, " +
                    "e.g. 100.101.102.103:8080 or my-pc.tailnet-name.ts.net:8080.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onSave(url) },
                enabled = url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save and connect") }

            if (canGoBack) {
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }
}
