package com.konprostart.tariffiacode.feature.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.konprostart.tariffiacode.R
import com.konprostart.tariffiacode.data.remote.RemoteProject
import com.konprostart.tariffiacode.data.remote.RemoteProjectStore
import com.konprostart.tariffiacode.runtime.RuntimeState

/** Settings screen that lists Remote Project mappings and edits/applies them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteProjectScreen(
    state: RemoteProjectUiState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onApply: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onOpenTerminal: () -> Unit,
    onFormChange: ((RemoteProjectForm) -> RemoteProjectForm) -> Unit,
    onSave: () -> Unit,
    onDismissEditor: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<RemoteProject?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.remote_project_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
                actions = {
                    IconButton(onClick = onAdd) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.remote_project_add))
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                        actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.remote_project_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VpsConnectionCard(
                state = state.connectionState,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onReconnect = onReconnect,
                onOpenTerminal = onOpenTerminal,
            )
            if (state.projects.isEmpty()) {
                Text(stringResource(R.string.remote_project_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.projects.forEach { project ->
                RemoteProjectRow(
                    project = project,
                    sshProfileName = state.sshProfiles.firstOrNull { it.id == project.sshProfileId }?.name,
                    dangling = project.id in state.danglingIds,
                    applied = state.appliedId == project.id,
                    onApply = { onApply(project.id) },
                    onEdit = { onEdit(project.id) },
                    onDelete = { pendingDelete = project },
                )
            }
            state.message?.let { message ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                ) {
                    Text(message, modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    state.form?.let { form ->
        RemoteProjectEditorDialog(
            form = form,
            profiles = state.sshProfiles.map { it.id to it.name },
            onChange = onFormChange,
            onSave = onSave,
            onDismiss = onDismissEditor,
        )
    }

    pendingDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.remote_project_delete)) },
            text = { Text(stringResource(R.string.remote_project_delete_confirm, project.label)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(project.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun VpsConnectionCard(
    state: RuntimeState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val connecting = state is RuntimeState.Connecting
    val connected = state is RuntimeState.Connected
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.remote_project_connection), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    text = stateLabel(state),
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (state is RuntimeState.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            (state as? RuntimeState.Failed)?.let { failed ->
                Text(failed.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            (state as? RuntimeState.Unavailable)?.let { unavailable ->
                Text(unavailable.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connected) {
                    OutlinedButton(onClick = onDisconnect) { Text(stringResource(R.string.ssh_disconnect)) }
                    Button(onClick = onReconnect) { Text(stringResource(R.string.remote_project_reconnect)) }
                } else {
                    Button(onClick = onConnect, enabled = !connecting) {
                        if (connecting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (connecting) stringResource(R.string.ssh_connecting) else stringResource(R.string.ssh_connect))
                    }
                }
                OutlinedButton(onClick = onOpenTerminal) { Text(stringResource(R.string.remote_terminal_title)) }
            }
        }
    }
}

@Composable
private fun stateLabel(state: RuntimeState): String =
    when (state) {
        RuntimeState.Disconnected -> stringResource(R.string.remote_project_disconnected)
        RuntimeState.Connecting -> stringResource(R.string.ssh_connecting)
        is RuntimeState.Connected -> stringResource(R.string.ssh_connected)
        is RuntimeState.Failed -> stringResource(R.string.remote_project_failed)
        is RuntimeState.Unavailable -> stringResource(R.string.remote_project_unavailable)
    }

@Composable
private fun RemoteProjectRow(
    project: RemoteProject,
    sshProfileName: String?,
    dangling: Boolean,
    applied: Boolean,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(project.label, fontWeight = FontWeight.SemiBold)
            Text(
                project.projectRef,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${sshProfileName ?: stringResource(R.string.remote_project_unknown_profile)} · ${project.remotePath}",
                style = MaterialTheme.typography.bodySmall,
                color = if (dangling) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (dangling) {
                Text(
                    stringResource(R.string.remote_project_dangling),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApply, enabled = !dangling) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (applied) stringResource(R.string.remote_project_applied) else stringResource(R.string.remote_project_apply))
                }
                OutlinedButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                OutlinedButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun RemoteProjectEditorDialog(
    form: RemoteProjectForm,
    profiles: List<Pair<String, String>>,
    onChange: ((RemoteProjectForm) -> RemoteProjectForm) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    var profileMenuOpen by remember { mutableStateOf(false) }
    val selectedName = profiles.firstOrNull { it.first == form.sshProfileId }?.second
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_project_add)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = form.projectRef,
                    onValueChange = { v -> onChange { it.copy(projectRef = v) } },
                    label = { Text(stringResource(R.string.remote_project_project)) },
                    singleLine = true,
                    isError = form.errors.containsKey(RemoteProjectStore.FIELD_PROJECT),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Box {
                    OutlinedButton(
                        onClick = { profileMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(selectedName ?: stringResource(R.string.remote_project_select_profile), modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = profileMenuOpen, onDismissRequest = { profileMenuOpen = false }) {
                        profiles.forEach { (id, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    onChange { it.copy(sshProfileId = id) }
                                    profileMenuOpen = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = form.remotePath,
                    onValueChange = { v -> onChange { it.copy(remotePath = v) } },
                    label = { Text(stringResource(R.string.remote_project_remote_path)) },
                    placeholder = { Text("/root/projects/app") },
                    singleLine = true,
                    isError = form.errors.containsKey(RemoteProjectStore.FIELD_REMOTE_PATH),
                    supportingText =
                        form.errors[RemoteProjectStore.FIELD_REMOTE_PATH]?.let {
                            { Text(stringResource(R.string.remote_project_path_invalid)) }
                        },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.displayName,
                    onValueChange = { v -> onChange { it.copy(displayName = v) } },
                    label = { Text(stringResource(R.string.remote_project_display_name)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
