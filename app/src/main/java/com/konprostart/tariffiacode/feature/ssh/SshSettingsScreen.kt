package com.konprostart.tariffiacode.feature.ssh

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.konprostart.tariffiacode.data.ssh.SshProfile

/** Settings screen that lists saved SSH servers and edits/connects them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshSettingsScreen(
    state: SshSettingsUiState,
    onBack: () -> Unit,
    onAddProfile: () -> Unit,
    onEditProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onFormChange: ((SshProfileForm) -> SshProfileForm) -> Unit,
    onSaveProfile: () -> Unit,
    onDismissEditor: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onTrustHostKey: () -> Unit,
    onDismissHostKey: () -> Unit,
) {
    var profilePendingDelete by remember { mutableStateOf<SshProfile?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { SshTopBar(onBack = onBack, onAddProfile = onAddProfile) },
    ) { paddingValues ->
        SshProfileList(
            state = state,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onEditProfile = onEditProfile,
            onDeleteProfile = { profilePendingDelete = it },
            contentPadding = paddingValues,
        )
    }

    state.form?.let { form ->
        SshProfileEditorDialog(
            form = form,
            onChange = onFormChange,
            onSave = onSaveProfile,
            onDismiss = onDismissEditor,
        )
    }

    state.pendingHostKey?.let { pending ->
        HostKeyDialog(pending = pending, onTrust = onTrustHostKey, onDismiss = onDismissHostKey)
    }

    profilePendingDelete?.let { profile ->
        DeleteProfileDialog(
            profile = profile,
            onConfirm = {
                onDeleteProfile(profile.id)
                profilePendingDelete = null
            },
            onDismiss = { profilePendingDelete = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SshTopBar(
    onBack: () -> Unit,
    onAddProfile: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.ssh_title), fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
            }
        },
        actions = {
            IconButton(onClick = onAddProfile) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.ssh_add_profile))
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
}

@Composable
private fun SshProfileList(
    state: SshSettingsUiState,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onEditProfile: (String) -> Unit,
    onDeleteProfile: (SshProfile) -> Unit,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.ssh_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.profiles.isEmpty()) {
            Text(
                text = stringResource(R.string.ssh_no_profiles),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.profiles.forEach { profile ->
            SshProfileRow(
                profile = profile,
                status = state.connection,
                onConnect = { onConnect(profile.id) },
                onDisconnect = onDisconnect,
                onEdit = { onEditProfile(profile.id) },
                onDelete = { onDeleteProfile(profile) },
            )
        }
        (state.connection as? SshConnectionStatus.Error)?.let { error ->
            ErrorBanner(message = error.message)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SshProfileRow(
    profile: SshProfile,
    status: SshConnectionStatus,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val connecting = status is SshConnectionStatus.Connecting && status.profileId == profile.id
    val connected = status is SshConnectionStatus.Connected && status.profileId == profile.id
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(profile.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${profile.username}@${profile.host}:${profile.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (connected) {
                    Text(
                        stringResource(R.string.ssh_connected),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = if (connected) onDisconnect else onConnect, enabled = !connecting) {
                    if (connecting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (connected) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (connecting) {
                            stringResource(R.string.ssh_connecting)
                        } else if (connected) {
                            stringResource(R.string.ssh_disconnect)
                        } else {
                            stringResource(R.string.ssh_connect)
                        },
                    )
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
private fun ErrorBanner(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(14.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun DeleteProfileDialog(
    profile: SshProfile,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ssh_delete_profile)) },
        text = { Text(stringResource(R.string.ssh_delete_confirm, profile.name)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
