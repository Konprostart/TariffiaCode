package com.konprostart.tariffiacode.feature.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.core.ssh.SshPortForward
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.data.ssh.SshProfileStore
import com.konprostart.tariffiacode.runtime.vps.VpsPortForwardManager
import com.konprostart.tariffiacode.runtime.vps.VpsPortForwardOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VpsPreviewUiState(
    val portInput: String = VpsPortForwardManager.DEFAULT_REMOTE_PORT.toString(),
    val isOpening: Boolean = false,
    /** The loopback URL to render once the tunnel is up; null while no preview is open. */
    val localUrl: String? = null,
    val error: String? = null,
    /** A server key the forward is waiting on; surface its fingerprint and let the user confirm. */
    val pendingHostKey: SshHostKey? = null,
)

/**
 * Drives the VPS live-preview: opens an SSH port forward from the selected SSH connection to a port
 * on the VPS (a dev server such as Vite on 5173), exposes the resulting loopback URL for the in-app
 * browser, and closes the forward when the screen goes away.
 */
class VpsPreviewViewModel(
    private val manager: VpsPortForwardManager,
    private val profiles: SshProfileStore,
    private val profileProvider: () -> SshProfile?,
    initialPort: Int? = null,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(
            VpsPreviewUiState(
                portInput = (initialPort ?: VpsPortForwardManager.DEFAULT_REMOTE_PORT).toString(),
            ),
        )
    val state: StateFlow<VpsPreviewUiState> = mutableState.asStateFlow()

    private var forward: SshPortForward? = null

    init {
        // A port carried in from a chat chip previews immediately; the manual form opens empty.
        if (initialPort != null && initialPort in 1..MAX_PORT) open()
    }

    fun setPort(value: String) {
        val digits = value.filter(Char::isDigit).take(MAX_PORT_DIGITS)
        mutableState.update { it.copy(portInput = digits, error = null) }
    }

    /** Open (or re-open) the tunnel to the entered port using the selected SSH connection. */
    fun open() {
        if (mutableState.value.isOpening) return
        val port = mutableState.value.portInput.toIntOrNull()
        if (port == null || port !in 1..MAX_PORT) {
            mutableState.update { it.copy(error = "Enter a port between 1 and $MAX_PORT") }
            return
        }
        val profile = profileProvider()
        if (profile == null) {
            mutableState.update { it.copy(error = "Select an SSH connection in Remote projects first") }
            return
        }
        closeForward()
        mutableState.update { it.copy(isOpening = true, error = null, localUrl = null, pendingHostKey = null) }
        viewModelScope.launch {
            when (val outcome = manager.open(profile, port)) {
                is VpsPortForwardOutcome.Connected -> {
                    forward = outcome.forward
                    mutableState.update { it.copy(isOpening = false, localUrl = outcome.localUrl) }
                }
                is VpsPortForwardOutcome.NeedsHostKeyTrust ->
                    mutableState.update {
                        it.copy(isOpening = false, pendingHostKey = outcome.hostKey, error = "The SSH host key is not trusted yet")
                    }
                VpsPortForwardOutcome.MissingCredential ->
                    mutableState.update { it.copy(isOpening = false, error = "No stored SSH credential for this connection") }
                is VpsPortForwardOutcome.Failed ->
                    mutableState.update { it.copy(isOpening = false, error = outcome.message) }
            }
        }
    }

    /** Persist the presented key on the profile, then retry the forward. */
    fun trustPendingHostKey() {
        val pending = mutableState.value.pendingHostKey ?: return
        val profile = profileProvider() ?: return
        profiles.upsert(profile.trusting(pending.sha256Fingerprint))
        mutableState.update { it.copy(pendingHostKey = null) }
        open()
    }

    fun dismissPendingHostKey() {
        mutableState.update { it.copy(pendingHostKey = null) }
    }

    /** Close the tunnel and clear the preview; the port field is kept. */
    fun stop() {
        closeForward()
        mutableState.update { it.copy(localUrl = null, error = null) }
    }

    private fun closeForward() {
        forward?.let { runCatching { it.close() } }
        forward = null
    }

    override fun onCleared() {
        closeForward()
    }

    private companion object {
        const val MAX_PORT = 65535
        const val MAX_PORT_DIGITS = 5
    }
}
