package com.konprostart.tariffiacode.feature.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppUpdateUiState(
    val installedVersion: String,
    val check: AppUpdateCheck? = null,
    val isChecking: Boolean = false,
    val error: String? = null,
)

/**
 * Drives the in-app "TariffiaCode update available" check.
 *
 * Reuses [AppUpdateReleaseClient] for the lookup and never installs anything: when an update is
 * available, the UI offers the official APK URL and the user starts the download themselves.
 */
class AppUpdateViewModel(
    installedVersion: String,
    private val client: AppUpdateReleaseClient = AppUpdateReleaseClient(),
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

    companion object {
        /** Factory so the installed version can be passed without an extra DI graph. */
        fun factory(installedVersion: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = AppUpdateViewModel(installedVersion) as T
            }
    }
}
