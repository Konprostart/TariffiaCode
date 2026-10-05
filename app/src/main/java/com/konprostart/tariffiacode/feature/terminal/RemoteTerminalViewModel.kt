package com.konprostart.tariffiacode.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.konprostart.tariffiacode.core.ssh.SshSession
import com.konprostart.tariffiacode.core.ssh.SshShellClient
import com.konprostart.tariffiacode.core.ssh.SshShellResult
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.data.ssh.SshProfileHostKeyVerifier
import com.konprostart.tariffiacode.data.ssh.resolveAuth
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RemoteTerminalUiState(
    val output: String = "",
    val isOpen: Boolean = false,
    val isConnecting: Boolean = false,
    val error: String? = null,
    val hostLabel: String? = null,
)

/**
 * Drives a remote shell over the existing SSH primitives ([SshShellClient]/[SshSession]) for the SSH
 * profile currently selected for the VPS runtime. It never logs or stores credentials; only the remote's
 * own output is shown.
 */
class RemoteTerminalViewModel(
    private val shellClient: SshShellClient,
    private val credentials: SshCredentialStore,
    private val profileProvider: () -> SshProfile?,
) : ViewModel() {
    private val _state = MutableStateFlow(RemoteTerminalUiState())
    val state: StateFlow<RemoteTerminalUiState> = _state.asStateFlow()

    private var session: SshSession? = null
    private var outputJob: Job? = null

    fun connect() {
        if (_state.value.isConnecting || session != null) return
        val profile = profileProvider()
        if (profile == null) {
            _state.update { it.copy(error = "Select a remote project / SSH connection first") }
            return
        }
        val credential = credentials.credential(profile.credentialRef)
        if (credential == null || !credential.isUsable) {
            _state.update { it.copy(error = "No stored SSH credential for this connection") }
            return
        }
        val auth = profile.resolveAuth(credential)
        if (auth == null) {
            _state.update { it.copy(error = "Stored credential does not match the connection's auth type") }
            return
        }

        _state.update { it.copy(isConnecting = true, error = null, output = "", hostLabel = "${profile.username}@${profile.host}") }
        viewModelScope.launch {
            val verifier = SshProfileHostKeyVerifier(profile)
            when (
                val result =
                    shellClient.openShell(
                        host = profile.host,
                        port = profile.port,
                        auth = auth,
                        verifier = verifier,
                    )
            ) {
                is SshShellResult.Opened -> {
                    session = result.session
                    _state.update { it.copy(isConnecting = false, isOpen = true) }
                    outputJob =
                        viewModelScope.launch {
                            result.session.output.collect { bytes ->
                                appendOutput(bytes.decodeToString())
                            }
                            // The flow completes when the remote closes the shell.
                            _state.update { it.copy(isOpen = false) }
                        }
                }
                is SshShellResult.HostKeyUntrusted -> {
                    _state.update {
                        it.copy(
                            isConnecting = false,
                            isOpen = false,
                            error = "SSH host key is not trusted (${result.hostKey.sha256Fingerprint})",
                        )
                    }
                }
                is SshShellResult.Failure -> {
                    _state.update { it.copy(isConnecting = false, isOpen = false, error = result.message) }
                }
            }
        }
    }

    fun send(input: String) {
        val target = session ?: return
        target.writeStandardInput(input.toByteArray(Charsets.UTF_8))
    }

    /** Send a line of input (adds a newline), the common case for a shell. */
    fun sendLine(input: String) {
        send("$input\n")
    }

    fun disconnect() {
        outputJob?.cancel()
        outputJob = null
        runCatching { session?.close() }
        session = null
        _state.update { it.copy(isOpen = false, isConnecting = false) }
    }

    override fun onCleared() {
        disconnect()
    }

    private fun appendOutput(text: String) {
        if (text.isEmpty()) return
        _state.update { current ->
            val combined = (current.output + text).takeLast(MAX_OUTPUT_CHARS)
            current.copy(output = combined)
        }
    }

    private companion object {
        const val MAX_OUTPUT_CHARS = 200_000
    }
}
