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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.konprostart.tariffiacode.core.router.RouterConfigSource

/**
 * Keys for the router, and nothing else.
 *
 * The screen shows which providers the routing table can actually reach and whether each one is
 * ready, which is the question that matters - a key entered for a provider that is not in any chain
 * is a key that will never be used, and one that looks set when it is not is worse.
 *
 * Keys are never displayed. A stored key is shown as its last four characters, and the field for
 * entering a new one starts blank rather than pre-filled: anything that can put a key on screen is a
 * place it ends up in a screenshot, a clipboard, or a bug report.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouterKeysScreen(
    onBack: () -> Unit,
    viewModel: RouterKeysViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Router keys") },
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
                Text(
                    text =
                        "Which routing table is running: " +
                            when (state.tableOrigin) {
                                RouterConfigSource.Origin.USER_FILE -> "your file"
                                RouterConfigSource.Origin.BUNDLED -> "the bundled copy"
                                null -> "unknown"
                            },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.tableWarning != null) {
                item {
                    Card {
                        Text(
                            text = state.tableWarning,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (state.userTablePath != null) {
                item {
                    Text(
                        text = "Edit your table at: ${state.userTablePath}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (!state.storeAvailable) {
                item {
                    Text(
                        text = "The encrypted key store could not be opened. No provider can be called.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (state.message != null) {
                item { Text(state.message!!, style = MaterialTheme.typography.bodySmall) }
            }

            items(state.providers) { provider ->
                ProviderRow(
                    provider = provider,
                    editing = state.editing == provider.provider,
                    draft = if (state.editing == provider.provider) state.draft else "",
                    onStartEditing = { viewModel.startEditing(provider.provider) },
                    onDraftChanged = viewModel::onDraftChanged,
                    onSave = viewModel::save,
                    onCancel = viewModel::cancelEditing,
                    onRemove = { viewModel.remove(provider.provider) },
                )
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
                        text =
                            if (provider.hasKey) {
                                "key set (${provider.keyHint})"
                            } else {
                                "no key - this provider will be skipped"
                            },
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
