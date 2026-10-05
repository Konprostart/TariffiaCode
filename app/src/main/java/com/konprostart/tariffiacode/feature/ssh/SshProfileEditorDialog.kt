package com.konprostart.tariffiacode.feature.ssh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.konprostart.tariffiacode.R
import com.konprostart.tariffiacode.data.ssh.SshAuthType

/** Create/edit dialog for an SSH profile. The credential fields are write-only. */
@Composable
internal fun SshProfileEditorDialog(
    form: SshProfileForm,
    onChange: ((SshProfileForm) -> SshProfileForm) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ssh_add_profile)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SshIdentityFields(form = form, onChange = onChange)
                SshAuthTypeSelector(form = form, onChange = onChange)
                SshCredentialFields(form = form, onChange = onChange)
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SshIdentityFields(
    form: SshProfileForm,
    onChange: ((SshProfileForm) -> SshProfileForm) -> Unit,
) {
    OutlinedTextField(
        value = form.name,
        onValueChange = { value -> onChange { it.copy(name = value) } },
        label = { Text(stringResource(R.string.ssh_profile_name)) },
        singleLine = true,
        isError = form.errors.containsKey(SshProfileValidator.FIELD_NAME),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.host,
        onValueChange = { value -> onChange { it.copy(host = value) } },
        label = { Text(stringResource(R.string.ssh_host)) },
        leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
        singleLine = true,
        isError = form.errors.containsKey(SshProfileValidator.FIELD_HOST),
        supportingText =
            form.errors[SshProfileValidator.FIELD_HOST]?.let {
                { Text(stringResource(R.string.ssh_host_invalid)) }
            },
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.port,
        onValueChange = { value -> onChange { it.copy(port = value) } },
        label = { Text(stringResource(R.string.ssh_port)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        isError = form.errors.containsKey(SshProfileValidator.FIELD_PORT),
        supportingText =
            form.errors[SshProfileValidator.FIELD_PORT]?.let {
                { Text(stringResource(R.string.ssh_port_invalid)) }
            },
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.username,
        onValueChange = { value -> onChange { it.copy(username = value) } },
        label = { Text(stringResource(R.string.ssh_username)) },
        singleLine = true,
        isError = form.errors.containsKey(SshProfileValidator.FIELD_USERNAME),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SshAuthTypeSelector(
    form: SshProfileForm,
    onChange: ((SshProfileForm) -> SshProfileForm) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = form.authType == SshAuthType.PASSWORD,
            onClick = { onChange { it.copy(authType = SshAuthType.PASSWORD) } },
            label = { Text(stringResource(R.string.ssh_auth_password)) },
        )
        FilterChip(
            selected = form.authType == SshAuthType.PRIVATE_KEY,
            onClick = { onChange { it.copy(authType = SshAuthType.PRIVATE_KEY) } },
            label = { Text(stringResource(R.string.ssh_auth_private_key)) },
        )
    }
}

@Composable
private fun SshCredentialFields(
    form: SshProfileForm,
    onChange: ((SshProfileForm) -> SshProfileForm) -> Unit,
) {
    val missing = form.errors.containsKey(SshProfileValidator.FIELD_CREDENTIAL) || form.isCredentialMissingForNew()
    when (form.authType) {
        SshAuthType.PASSWORD -> {
            var visible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = form.password,
                onValueChange = { value -> onChange { it.copy(password = value) } },
                label = { Text(stringResource(R.string.ssh_password)) },
                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null,
                        )
                    }
                },
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                isError = missing,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SshAuthType.PRIVATE_KEY -> {
            OutlinedTextField(
                value = form.privateKeyPem,
                onValueChange = { value -> onChange { it.copy(privateKeyPem = value) } },
                label = { Text(stringResource(R.string.ssh_private_key_pem)) },
                minLines = 3,
                isError = missing,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.passphrase,
                onValueChange = { value -> onChange { it.copy(passphrase = value) } },
                label = { Text(stringResource(R.string.ssh_passphrase)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (form.credentialAlreadyStored) {
        Text(
            stringResource(R.string.ssh_credential_stored),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (missing && form.errors.containsKey(SshProfileValidator.FIELD_CREDENTIAL)) {
        Text(
            stringResource(R.string.ssh_credential_required),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
internal fun HostKeyDialog(
    pending: PendingHostKey,
    onTrust: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (pending.mismatch) R.string.ssh_host_key_mismatch_title else R.string.ssh_host_key_title,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        if (pending.mismatch) R.string.ssh_host_key_mismatch_message else R.string.ssh_host_key_message,
                    ),
                )
                if (!pending.mismatch) {
                    Text(
                        pending.hostKey.sha256Fingerprint,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        confirmButton = {
            if (!pending.mismatch) {
                TextButton(onClick = onTrust) { Text(stringResource(R.string.ssh_host_key_trust)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
