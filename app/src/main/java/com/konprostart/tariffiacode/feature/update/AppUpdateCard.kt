package com.konprostart.tariffiacode.feature.update

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.konprostart.tariffiacode.R
import java.io.File

/**
 * Hosts [AppUpdateCard] inside the settings app-settings section, owning its own [AppUpdateViewModel]
 * so the rest of the settings screen stays untouched. On first composition it runs a check.
 */
@Composable
fun AppUpdateSectionCard(
    installedVersion: String,
    installedVersionCode: Long,
    channel: AppUpdateChannel = AppUpdateChannel.Release,
    githubTokenProvider: () -> String? = { null },
    context: Context = LocalContext.current,
    viewModel: AppUpdateViewModel =
        viewModel(
            factory =
                AppUpdateViewModel.factory(
                    installedVersion = installedVersion,
                    installedVersionCode = installedVersionCode,
                    githubTokenProvider = githubTokenProvider,
                    installer = AndroidAppUpdateInstaller(context.applicationContext),
                    apkFileProvider = { File(context.applicationContext.cacheDir, "updates/tariffiacode-update.apk") },
                    channel = channel,
                ),
        ),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.checkForUpdate() }
    AppUpdateCard(
        state = state,
        onCheck = viewModel::checkForUpdate,
        onDownload = viewModel::downloadAndInstall,
    )
}

/**
 * Minimal "TariffiaCode update" card: checks GitHub Releases for a newer published APK and, when one
 * exists, offers the official download. [onDownload] downloads the APK, verifies its SHA-256 against
 * the release digest, and hands it to the system installer, which the user still confirms.
 */
@Composable
fun AppUpdateCard(
    state: AppUpdateUiState,
    onCheck: () -> Unit,
    onDownload: (AppUpdateRelease) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            stringResource(R.string.app_update_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (state.isChecking || state.isDownloading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
    Spacer(Modifier.padding(vertical = 4.dp))

    when (val check = state.check) {
        null -> {
            Text(
                stringResource(R.string.app_update_source_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.padding(vertical = 5.dp))
            OutlinedButton(
                onClick = onCheck,
                enabled = !state.isChecking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_check_update))
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(stringResource(R.string.check_for_update_button))
            }
        }
        is AppUpdateCheck.UpToDate -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.cd_up_to_date),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.app_up_to_date_label, check.currentVersion),
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.padding(vertical = 4.dp))
            OutlinedButton(
                onClick = onCheck,
                enabled = !state.isChecking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.recheck_button))
            }
        }
        is AppUpdateCheck.Available -> {
            Text(
                stringResource(R.string.app_update_available_label, check.release.version),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.padding(vertical = 3.dp))
            Text(
                stringResource(R.string.app_update_installed_version, check.currentVersion),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.app_update_available_version, check.release.version),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            val channelLabel =
                stringResource(
                    if (check.release.channel == AppUpdateChannel.Debug) {
                        R.string.app_update_channel_debug
                    } else {
                        R.string.app_update_channel_release
                    },
                )
            Text(
                stringResource(
                    R.string.app_update_build_details,
                    channelLabel,
                    check.release.applicationId,
                    check.release.version,
                    check.release.versionCode?.toString() ?: stringResource(R.string.app_update_metadata_unavailable),
                    check.release.commitSha ?: stringResource(R.string.app_update_metadata_unavailable),
                    check.release.apkUrl,
                    check.release.sha256,
                    check.release.signerSha256 ?: stringResource(R.string.app_update_metadata_unavailable),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.padding(vertical = 6.dp))
            Button(
                onClick = { onDownload(check.release) },
                enabled = !state.isChecking && !state.isDownloading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.SystemUpdate, contentDescription = stringResource(R.string.cd_update))
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(stringResource(R.string.app_update_download_button, check.release.version))
            }
        }
    }

    state.installMessage?.let { message ->
        Spacer(Modifier.padding(vertical = 4.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }

    state.error?.let { error ->
        Spacer(Modifier.padding(vertical = 4.dp))
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
