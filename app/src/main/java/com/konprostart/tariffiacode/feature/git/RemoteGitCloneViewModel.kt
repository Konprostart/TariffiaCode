package com.konprostart.tariffiacode.feature.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.konprostart.tariffiacode.data.remote.RemoteGitCloneRequest
import com.konprostart.tariffiacode.data.ssh.SshProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RemoteGitCloneUiState(
    val repositoryUrl: String = "",
    val remotePath: String = "",
    val isCloning: Boolean = false,
    val success: Boolean = false,
    val output: String = "",
    val errors: Map<String, String> = emptyMap(),
    val message: String? = null,
)

/**
 * Drives a remote `git clone` onto the VPS through the existing SSH shell. It never logs or stores
 * credentials; only git's own output is surfaced.
 */
class RemoteGitCloneViewModel(
    private val cloner: RemoteGitCloner,
    private val profileProvider: () -> SshProfile?,
) : ViewModel() {
    private val _state = MutableStateFlow(RemoteGitCloneUiState())
    val state: StateFlow<RemoteGitCloneUiState> = _state.asStateFlow()

    fun updateUrl(value: String) {
        _state.update { it.copy(repositoryUrl = value, errors = emptyMap(), message = null) }
    }

    fun updatePath(value: String) {
        _state.update { it.copy(remotePath = value, errors = emptyMap(), message = null) }
    }

    fun clone() {
        if (_state.value.isCloning) return
        val profile = profileProvider()
        if (profile == null) {
            _state.update { it.copy(message = "Select a remote project / SSH connection first") }
            return
        }
        val request = RemoteGitCloneRequest(_state.value.repositoryUrl, _state.value.remotePath)
        _state.update { it.copy(isCloning = true, success = false, output = "", errors = emptyMap(), message = null) }
        viewModelScope.launch {
            when (val result = cloner.clone(profile, request)) {
                is RemoteGitCloneResult.Success ->
                    _state.update { it.copy(isCloning = false, success = true, output = result.output) }
                is RemoteGitCloneResult.Invalid ->
                    _state.update { it.copy(isCloning = false, errors = result.errors) }
                RemoteGitCloneResult.TargetNotEmpty ->
                    _state.update { it.copy(isCloning = false, message = "The remote path already exists and is not empty") }
                RemoteGitCloneResult.MissingCredential ->
                    _state.update { it.copy(isCloning = false, message = "No stored SSH credential for this connection") }
                is RemoteGitCloneResult.HostKeyUntrusted ->
                    _state.update {
                        it.copy(isCloning = false, message = "SSH host key is not trusted (${result.hostKey.sha256Fingerprint})")
                    }
                is RemoteGitCloneResult.Failed ->
                    _state.update { it.copy(isCloning = false, message = result.message, output = result.output) }
            }
        }
    }
}
