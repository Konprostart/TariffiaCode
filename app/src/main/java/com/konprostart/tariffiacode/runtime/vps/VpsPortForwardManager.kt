package com.konprostart.tariffiacode.runtime.vps

import com.konprostart.tariffiacode.core.ssh.SshFailureDiagnostic
import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.core.ssh.SshPortForward
import com.konprostart.tariffiacode.core.ssh.SshPortForwardResult
import com.konprostart.tariffiacode.core.ssh.SshPortForwarder
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.data.ssh.SshProfileHostKeyVerifier
import com.konprostart.tariffiacode.data.ssh.resolveAuth

/** Outcome of tunnelling a VPS port to the device's loopback for a live preview. */
sealed interface VpsPortForwardOutcome {
    /**
     * The forward is listening. [localUrl] is the loopback URL to open in the in-app browser;
     * [forward] must be closed when the preview is dismissed.
     */
    data class Connected(
        val localUrl: String,
        val forward: SshPortForward,
    ) : VpsPortForwardOutcome

    /** The server key is not yet trusted; surface it and let the user confirm before trusting. */
    data class NeedsHostKeyTrust(
        val hostKey: SshHostKey,
    ) : VpsPortForwardOutcome

    /** The profile has no usable stored credential, or it does not match the profile's auth type. */
    data object MissingCredential : VpsPortForwardOutcome

    /** The forward could not be established. */
    data class Failed(
        val message: String,
    ) : VpsPortForwardOutcome
}

/**
 * Opens an SSH local port forward from the device's loopback to a service bound to the VPS's own
 * loopback - a dev server such as Vite on 5173 - so the in-app browser can preview it.
 *
 * Reuses the same [SshPortForwarder] transport and credential/TOFU path as [VpsRuntimeConnector];
 * unlike it, this build no OpenCode [com.konprostart.tariffiacode.data.connection.ConnectionProfile]
 * and so works for any remote port. It adds no transport or credential logic of its own.
 */
class VpsPortForwardManager(
    private val forwarder: SshPortForwarder,
    private val credentials: SshCredentialStore,
) {
    suspend fun open(
        profile: SshProfile,
        remotePort: Int,
        remoteHost: String = DEFAULT_REMOTE_HOST,
        connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    ): VpsPortForwardOutcome {
        val credential = credentials.credential(profile.credentialRef)
        if (credential == null || !credential.isUsable) return VpsPortForwardOutcome.MissingCredential
        val auth = profile.resolveAuth(credential) ?: return VpsPortForwardOutcome.MissingCredential

        val verifier = SshProfileHostKeyVerifier(profile)
        return when (
            val result =
                forwarder.openLocalForward(
                    host = profile.host,
                    port = profile.port,
                    auth = auth,
                    verifier = verifier,
                    remoteHost = remoteHost,
                    remotePort = remotePort,
                    connectTimeoutMillis = connectTimeoutMillis,
                )
        ) {
            is SshPortForwardResult.Listening -> {
                val forward = result.forward
                VpsPortForwardOutcome.Connected(
                    localUrl = "http://${forward.localHost}:${forward.localPort}/",
                    forward = forward,
                )
            }
            is SshPortForwardResult.HostKeyUntrusted -> VpsPortForwardOutcome.NeedsHostKeyTrust(result.hostKey)
            is SshPortForwardResult.Failure ->
                VpsPortForwardOutcome.Failed(SshFailureDiagnostic.format(result.message, result.cause, auth))
        }
    }

    companion object {
        /** A dev server on the VPS is normally bound to the server's own loopback. */
        const val DEFAULT_REMOTE_HOST = "127.0.0.1"

        /** The default Vite dev-server port, offered as the first preview choice. */
        const val DEFAULT_REMOTE_PORT = 5173

        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000L
    }
}
