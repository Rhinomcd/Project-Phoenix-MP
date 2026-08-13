package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.devil.phoenixproject.data.sync.PortalServerEntry
import com.devil.phoenixproject.presentation.viewmodel.PortalServersViewModel
import com.devil.phoenixproject.ui.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * Lets the user pick which Portal (Supabase) backend the app talks to and register
 * self-hosted ones. A change applies on the next app launch — see PortalServerRepository.
 */
@Composable
fun PortalServersScreen(
    onSetTitle: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: PortalServersViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        onSetTitle("Portal Server")
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        if (uiState.restartRequired) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Text(
                    "Restart the app to apply the new server.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(Spacing.medium),
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = Spacing.small)) {
                uiState.servers.forEach { server ->
                    PortalServerRow(
                        server = server,
                        selected = server.id == uiState.selectedId,
                        enabled = !uiState.isLinked,
                        onSelect = { viewModel.selectServer(server.id) },
                        onRemove = { viewModel.removeServer(server.id) },
                    )
                }
            }
        }

        if (uiState.isLinked) {
            Text(
                "Unlink your Portal account to switch servers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedButton(
            onClick = viewModel::showAddDialog,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add server")
        }
    }

    if (uiState.addDialogVisible) {
        AddPortalServerDialog(
            errorMessage = uiState.addError,
            onConfirm = viewModel::addServer,
            onDismiss = viewModel::dismissAddDialog,
        )
    }
}

@Composable
private fun PortalServerRow(
    server: PortalServerEntry,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            enabled = enabled,
        )
        Spacer(Modifier.width(Spacing.small))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                server.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                server.host,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!server.isBuiltIn) {
            IconButton(
                onClick = onRemove,
                // Removing the active server would silently move the app to the built-in
                // one; make the user switch away first.
                enabled = !selected,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove ${server.name}",
                )
            }
        }
    }
}

@Composable
private fun AddPortalServerDialog(
    errorMessage: String?,
    onConfirm: (name: String, url: String, anonKey: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var anonKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add server", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text(
                    "Point the app at a self-hosted Portal backend.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                )
                if (url.trim().startsWith("http://")) {
                    Text(
                        "http:// sends your tokens unencrypted. Use https:// unless this server is on a trusted network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = anonKey,
                    onValueChange = { anonKey = it },
                    label = { Text("Anon key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                )
                errorMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, url, anonKey) },
                enabled = name.isNotBlank() && url.isNotBlank() && anonKey.isNotBlank(),
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
