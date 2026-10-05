package com.konprostart.tariffiacode.feature.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.konprostart.tariffiacode.core.api.OpenCodeFileChange
import com.konprostart.tariffiacode.data.ssh.SshProfile
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
    val isPulling: Boolean = false,
    val pullSuccess: Boolean = false,
    val pullMessage: String? = null,
    val pullOutput: String = "",
)

/**
 * Read-only git status/diff for the mapped remote project on the VPS, plus a `git pull` refresh.
 *
 * Status/diff reuse the existing OpenCode `vcs*` API through the selected runtime's [OpenCodeBackend] — the
 * same calls the file explorer already makes. Pull runs `git pull --ff-only` on the VPS through the existing
 * SSH shell executor (the clone workflow), for the mapped remote path. No new backend, no SFTP/SCP, no
 * transport change, and no credential is ever shown.
 */
class RemoteGitViewModel(
    private val backend: OpenCodeBackend,
    private val directoryProvider: () -> String?,
    private val puller: RemoteGitPuller,
    private val profileProvider: () -> SshProfile?,
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

    fun pull() {
        if (_state.value.isPulling) return
        val directory = directoryProvider()?.trim()
        if (directory.isNullOrEmpty()) {
            _state.update { it.copy(pullMessage = "Apply a remote project before pulling") }
            return
        }
        val profile = profileProvider()
        if (profile == null) {
            _state.update { it.copy(pullMessage = "Select an SSH connection first") }
            return
        }
        _state.update { it.copy(isPulling = true, pullSuccess = false, pullOutput = "", pullMessage = null) }
        viewModelScope.launch {
            when (val result = puller.pull(profile, directory)) {
                is RemoteGitPullResult.Success ->
                    _state.update { it.copy(isPulling = false, pullSuccess = true, pullOutput = result.output) }
                is RemoteGitPullResult.Invalid ->
                    _state.update { it.copy(isPulling = false, pullMessage = "Invalid remote path") }
                RemoteGitPullResult.MissingCredential ->
                    _state.update { it.copy(isPulling = false, pullMessage = "No stored SSH credential for this connection") }
                is RemoteGitPullResult.HostKeyUntrusted ->
                    _state.update {
                        it.copy(isPulling = false, pullMessage = "SSH host key is not trusted (${result.hostKey.sha256Fingerprint})")
                    }
                is RemoteGitPullResult.Failed ->
                    _state.update { it.copy(isPulling = false, pullMessage = result.message, pullOutput = result.output) }
            }
        }
    }
}
