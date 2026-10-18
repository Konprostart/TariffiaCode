package com.konprostart.tariffiacode.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.konprostart.tariffiacode.core.router.RouterConfigSource
import com.konprostart.tariffiacode.runtime.vps.VpsTunnelState

/**
 * The router, on one screen: whether it is on, and which keys it has.
 *
 * These are two halves of one thing and were two screens, which meant the answer to "is my key
 * being used" was always on a different page from the switch that uses it. A provider with no key
 * while the router is switched on looks like a failure and is not - it is a chain entry that will be
 * skipped - so both belong where the other is visible.
 *
 * The state is the service's own, read from the file the service writes. A switch that showed "on"
 * over a service that had died two lines after starting would be worse than no switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouterScreen(
    onBack: () -> Unit,
    control: RouterControlViewModel = viewModel(),
    keys: RouterKeysViewModel = viewModel(),
) {
    val routerState by control.state.collectAsStateWithLifecycle()
    val keyState by keys.state.collectAsStateWithLifecycle()

    // The service runs in its own process, so nothing pushes a change here; the screen looks.
    LaunchedEffect(Unit) {
        while (true) {
            control.refresh()
            kotlinx.coroutines.delay(2_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Router") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (routerState.isRunning) "Running" else "Stopped",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                val failure = routerState.failure
                                Text(
                                    text =
                                        when {
                                            routerState.isRunning ->
                                                "Serving on 127.0.0.1:${routerState.port}"
                                            failure != null ->
                                                failure
                                            else ->
                                                "Agents will reach providers directly"
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(
                                checked = routerState.isRunning,
                                enabled = !routerState.pending,
                                onCheckedChange = { on -> if (on) control.start() else control.stop() },
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        // A full-width button, below the status rather than beside it.
                        //
                        // The switch alone was a poor way to start a server on a phone: small target,
                        // and it shares a row with a status line that can run to several lines. If the
                        // two ever contend for that row, the one that disappears is the control, not
                        // the text. The button cannot lose that argument - it has the whole card.
                        Button(
                            onClick = { if (routerState.isRunning) control.stop() else control.start() },
                            enabled = !routerState.pending,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                when {
                                    routerState.pending -> "Working..."
                                    routerState.isRunning -> "Stop router"
                                    else -> "Start router"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }

            item {
                VpsTunnelCard(
                    state = routerState,
                    onToggle = control::setTunnelEnabled,
                    onStart = control::startTunnel,
                    onStop = control::stopTunnel,
                    onTrustHostKey = control::trustTunnelHostKey,
                    onDismissHostKey = control::dismissTunnelHostKey,
                )
            }

            item {
                Text(
                    text =
                        "Routing table: " +
                            when (keyState.tableOrigin) {
                                RouterConfigSource.Origin.USER_FILE -> "your file"
                                RouterConfigSource.Origin.BUNDLED -> "the bundled copy"
                                null -> "unknown"
                            },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (keyState.userTablePath != null) {
                item {
                    Text(
                        text = "Edit your table at: ${keyState.userTablePath}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            val keyMessage = keyState.message
            if (keyMessage != null) {
                item { Text(keyMessage, style = MaterialTheme.typography.bodySmall) }
            }

            items(keyState.providers) { provider ->
                ProviderRow(
                    provider = provider,
                    editing = keyState.editing == provider.provider,
                    draft = if (keyState.editing == provider.provider) keyState.draft else "",
                    onStartEditing = { keys.startEditing(provider.provider) },
                    onDraftChanged = keys::onDraftChanged,
                    onSave = keys::save,
                    onCancel = keys::cancelEditing,
                    onRemove = { keys.remove(provider.provider) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * The user's own VPS, kept reachable from the router.
 *
 * A separate card rather than another row under the router switch, because it is a separate thing:
 * the router can be on with every chain entry pointing at a cloud provider, and the tunnel can be up
 * with the router off. What ties them together is the switch - a tunnel that comes back on its own
 * when the router starts, so a chain entry pointing at the VPS is not the first thing an agent
 * discovers to be down.
 */
@Composable
private fun VpsTunnelCard(
    state: RouterControlUiState,
    onToggle: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTrustHostKey: () -> Unit,
    onDismissHostKey: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("VPS tunnel", style = MaterialTheme.typography.titleMedium)
            Text(
                text =
                    when {
                        state.tunnelIsOpen -> "Open at ${(state.tunnel as VpsTunnelState.Open).localUrl}"
                        state.tunnel is VpsTunnelState.Opening -> "Opening..."
                        state.tunnelEnabled -> "Kept open in the background"
                        else -> "Stopped"
                    },
                style = MaterialTheme.typography.bodySmall,
            )
            state.tunnelError?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            val hostKey = state.tunnelHostKey
            if (hostKey != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "The server key is not trusted yet: ${hostKey.sha256Fingerprint}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onTrustHostKey) { Text("Trust and retry") }
                    OutlinedButton(onClick = onDismissHostKey) { Text("Not now") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Keep it open in the background", style = MaterialTheme.typography.bodyMedium)
                    Text("Starts with the router and again after the app restarts", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = state.tunnelEnabled,
                    enabled = !state.tunnelPending,
                    onCheckedChange = onToggle,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.tunnelIsOpen) {
                    OutlinedButton(onClick = onStop, enabled = !state.tunnelPending) { Text("Stop tunnel") }
                } else {
                    Button(onClick = onStart, enabled = !state.tunnelPending) { Text("Start tunnel") }
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    provider: RouterProviderUi,
    editing: Boolean,
    draft: String,
    onStartEditing: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(provider.provider, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (provider.hasKey) "key set (${provider.keyHint})" else "no key - will be skipped",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!editing) {
                    IconButton(onClick = onStartEditing) {
                        Icon(Icons.Default.Edit, contentDescription = "Set key for ${provider.provider}")
                    }
                    if (provider.hasKey) {
                        IconButton(onClick = onRemove) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove key for ${provider.provider}")
                        }
                    }
                }
            }
            if (editing) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChanged,
                    label = { Text("Key for ${provider.provider}") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSave) { Text("Save") }
                    Button(onClick = onCancel) { Text("Cancel") }
                }
            }
        }
    }
}
