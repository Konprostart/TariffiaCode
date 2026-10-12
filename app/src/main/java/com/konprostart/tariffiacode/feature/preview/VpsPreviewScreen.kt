package com.konprostart.tariffiacode.feature.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.konprostart.tariffiacode.R
import com.konprostart.tariffiacode.feature.browser.GuestWebView

/**
 * Live preview of a dev server running on the VPS. The [VpsPreviewViewModel] opens an SSH port
 * forward to the entered port; once it is up, the shared [GuestWebView] renders the loopback URL so
 * the user can click through and test the running app in real time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpsPreviewScreen(
    state: VpsPreviewUiState,
    onBack: () -> Unit,
    onPortChange: (String) -> Unit,
    onOpen: () -> Unit,
    onStop: () -> Unit,
    onTrustHostKey: () -> Unit,
    onDismissHostKey: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vps_preview_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.portInput,
                        onValueChange = onPortChange,
                        label = { Text(stringResource(R.string.vps_preview_port)) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    if (state.localUrl == null) {
                        Button(onClick = onOpen, enabled = !state.isOpening) {
                            if (state.isOpening) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(stringResource(R.string.vps_preview_open))
                        }
                    } else {
                        OutlinedButton(onClick = onStop) { Text(stringResource(R.string.vps_preview_stop)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    COMMON_DEV_PORTS.forEach { port ->
                        OutlinedButton(onClick = { onPortChange(port) }) { Text(port) }
                    }
                }
                state.error?.let { error ->
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                state.pendingHostKey?.let { key ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                stringResource(R.string.vps_preview_trust_prompt, key.sha256Fingerprint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onTrustHostKey) { Text(stringResource(R.string.vps_preview_trust)) }
                                TextButton(onClick = onDismissHostKey) { Text(stringResource(R.string.vps_preview_dismiss)) }
                            }
                        }
                    }
                }
            }

            val url = state.localUrl
            if (url != null) {
                Text(
                    url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                // Recreate the WebView when the tunnel URL changes so the new target is loaded.
                key(url) {
                    GuestWebView(url = url, modifier = Modifier.weight(1f))
                }
            } else {
                Text(
                    stringResource(R.string.vps_preview_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

/** Ports offered as one-tap choices for the common front-end dev servers. */
private val COMMON_DEV_PORTS = listOf("3000", "5173", "8080")
