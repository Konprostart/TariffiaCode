package com.konprostart.tariffiacode.feature.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class AppUpdateUiState(
    val installedVersion: String,
    val check: AppUpdateCheck? = null,
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val installMessage: String? = null,
    val error: String? = null,
)

/**
 * Drives the in-app "TariffiaCode update available" flow.
 *
 * Reuses [AppUpdateReleaseClient] for the lookup, then downloads the official release APK and hands it to
 * the system package installer via [AppUpdateInstaller]. When the app lacks permission to install
 * packages it sends the user to the system screen instead. Never installs silently; the user confirms the
 * standard Android install/update prompt.
 */
class AppUpdateViewModel(
    installedVersion: String,
    private val client: AppUpdateReleaseClient = AppUpdateReleaseClient(),
    private val downloader: AppUpdateApkDownloader = OkHttpAppUpdateApkDownloader(),
    private val installer: AppUpdateInstaller,
    private val apkFileProvider: () -> File,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AppUpdateUiState(installedVersion = installedVersion))
    val state: StateFlow<AppUpdateUiState> = mutableState.asStateFlow()

    fun checkForUpdate() {
        if (mutableState.value.isChecking) return
        mutableState.update { it.copy(isChecking = true, error = null) }
        viewModelScope.launch {
            runCatching { client.check(mutableState.value.installedVersion) }
                .onSuccess { result ->
                    mutableState.update { it.copy(check = result, isChecking = false, error = null) }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(isChecking = false, error = error.message ?: "Could not check for updates")
                    }
                }
        }
    }

    /** Download the release APK and start the system install/update prompt. */
    fun downloadAndInstall(apkUrl: String) {
        if (mutableState.value.isDownloading) return
        if (!installer.canInstall()) {
            installer.requestInstallPermission()
            mutableState.update { it.copy(installMessage = "Allow installing unknown apps, then tap again") }
            return
        }
        mutableState.update { it.copy(isDownloading = true, installMessage = null, error = null) }
        viewModelScope.launch {
            runCatching {
                val destination = apkFileProvider()
                downloader.download(apkUrl, destination)
                destination
            }
                .onSuccess { apk ->
                    runCatching { installer.install(apk) }
                        .onSuccess { mutableState.update { it.copy(isDownloading = false, installMessage = "Starting installer…") } }
                        .onFailure { error ->
                            mutableState.update {
                                it.copy(isDownloading = false, error = error.message ?: "Could not start the installer")
                            }
                        }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(isDownloading = false, error = error.message ?: "Could not download the update") }
                }
        }
    }

    companion object {
        /** Factory so the installed version, update channel and Android collaborators need no DI graph. */
        fun factory(
            installedVersion: String,
            installer: AppUpdateInstaller,
            apkFileProvider: () -> File,
            channel: AppUpdateChannel = AppUpdateChannel.Release,
            downloader: AppUpdateApkDownloader = OkHttpAppUpdateApkDownloader(),
            client: AppUpdateReleaseClient = AppUpdateReleaseClient(channel),
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppUpdateViewModel(installedVersion, client, downloader, installer, apkFileProvider) as T
            }
    }
}
