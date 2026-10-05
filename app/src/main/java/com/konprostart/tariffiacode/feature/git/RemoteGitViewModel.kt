package com.konprostart.tariffiacode.feature.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.konprostart.tariffiacode.core.api.OpenCodeFileChange
import com.konprostart.tariffiacode.runtime.OpenCodeBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RemoteGitUiState(
    val directory: String? = null,
    val branch: String? = null,
    val changes: List<OpenCodeFileChange> = emptyList(),
    val diffs: List<OpenCodeFileChange> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

/**
 * Read-only git status/diff for the mapped remote project on the VPS.
 *
 * It reuses the existing OpenCode `vcs*` API through the selected runtime's [OpenCodeBackend] — the same
 * calls the file explorer already makes — so it needs no new backend, no shell execution and no SFTP/SCP.
 * The [directoryProvider] supplies the Remote Project's remote path (the mapping), which is passed as the
 * OpenCode `directory`; the resolver in the runtime keeps that behaviour consistent with sessions.
 */
class RemoteGitViewModel(
    private val backend: OpenCodeBackend,
    private val directoryProvider: () -> String?,
) : ViewModel() {
    private val _state = MutableStateFlow(RemoteGitUiState())
    val state: StateFlow<RemoteGitUiState> = _state.asStateFlow()

    fun refresh() {
        val directory = directoryProvider()?.trim()
        if (directory.isNullOrEmpty()) {
            _state.value = RemoteGitUiState(error = "Apply a remote project before viewing git")
            return
        }
        _state.update { it.copy(directory = directory, isLoading = true, error = null) }
        viewModelScope.launch {
            val info = runCatching { backend.vcsInfo(directory) }
            val status = runCatching { backend.vcsStatus(directory) }
            val diffs = runCatching { backend.vcsDiff(directory, "git", null) }
            _state.update {
                it.copy(
                    branch = info.getOrNull()?.branch,
                    changes = status.getOrDefault(emptyList()),
                    diffs = diffs.getOrDefault(emptyList()),
                    isLoading = false,
                    error =
                        (info.exceptionOrNull() ?: status.exceptionOrNull() ?: diffs.exceptionOrNull())
                            ?.message,
                )
            }
        }
    }
}
