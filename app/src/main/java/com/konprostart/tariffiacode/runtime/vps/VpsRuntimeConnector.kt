package com.konprostart.tariffiacode.runtime.vps

import com.konprostart.tariffiacode.core.ssh.SshFailureDiagnostic
import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.core.ssh.SshPortForward
import com.konprostart.tariffiacode.core.ssh.SshPortForwardResult
import com.konprostart.tariffiacode.core.ssh.SshPortForwarder
import com.konprostart.tariffiacode.data.connection.ConnectionProfile
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.data.ssh.SshProfileHostKeyVerifier
import com.konprostart.tariffiacode.data.ssh.resolveAuth

/** Outcome of establishing the SSH forward that exposes a VPS's loopback OpenCode server. */
sealed interface VpsConnectOutcome {
    /**
     * The forward is running. [profile] is the loopback OpenCode endpoint to hand to the existing
     * remote backend; [forward] must be closed when the runtime disconnects.
     */
    data class Connected(
        val profile: ConnectionProfile,
        val forward: SshPortForward,
    ) : VpsConnectOutcome

    /** The server key is not yet trusted; surface it and let the user confirm before trusting. */
    data class NeedsHostKeyTrust(
        val hostKey: SshHostKey,
    ) : VpsConnectOutcome

    /** The presented key differs from the trusted one; refuse and never offer to trust it. */
    data class HostKeyMismatch(
        val expectedFingerprint: String,
        val presentedFingerprint: String,
    ) : VpsConnectOutcome

    /** The profile has no usable stored credential, or it does not match the profile's auth type. */
    data object MissingCredential : VpsConnectOutcome

    /** The forward could not be established. */
    data class Failed(
        val message: String,
    ) : VpsConnectOutcome
}

/**
 * Establishes the SSH local port forward that connects TariffiaCode to an already-installed OpenCode HTTP
 * server on a VPS. Reuses the PR #27 [SshPortForwarder] and the PR #26 [SshCredentialStore]; it adds no
 * transport or credential logic of its own.
 *
 * The forward targets the VPS's own loopback ([DEFAULT_REMOTE_HOST]:[DEFAULT_REMOTE_PORT] by default),
 * because that is where `opencode serve` listens. The resulting loopback [ConnectionProfile] is consumed by
 * the existing remote OpenCode backend unchanged.
 */
class VpsRuntimeConnector(
    private val forwarder: SshPortForwarder,
    private val credentials: SshCredentialStore,
) {
    /**
     * Open the forward for [profile]. On success the caller owns the returned [SshPortForward] and must
     * close it (normally via the runtime's disconnect).
     */
    suspend fun connect(
        profile: SshProfile,
        remoteHost: String = DEFAULT_REMOTE_HOST,
        remotePort: Int = DEFAULT_REMOTE_PORT,
        opencodeUsername: String = DEFAULT_OPENCODE_USERNAME,
        connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    ): VpsConnectOutcome {
        val credential = credentials.credential(profile.credentialRef)
        if (credential == null || !credential.isUsable) return VpsConnectOutcome.MissingCredential
        val auth = profile.resolveAuth(credential) ?: return VpsConnectOutcome.MissingCredential

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
                VpsConnectOutcome.Connected(
                    profile =
                        ConnectionProfile(
                            id = vpsProfileId(profile),
                            name = profile.name,
                            baseUrl = "http://${forward.localHost}:${forward.localPort}/",
                            username = opencodeUsername,
                            allowInsecureLan = true,
                        ),
                    forward = forward,
                )
            }
            is SshPortForwardResult.HostKeyUntrusted -> {
                val presented = verifier.presentedKey
                val expected = profile.trustedHostKeyFingerprint
                if (verifier.isMismatch() && presented != null && !expected.isNullOrBlank()) {
                    VpsConnectOutcome.HostKeyMismatch(expected, presented.sha256Fingerprint)
                } else {
                    VpsConnectOutcome.NeedsHostKeyTrust(result.hostKey)
                }
            }
            is SshPortForwardResult.Failure ->
                VpsConnectOutcome.Failed(SshFailureDiagnostic.format(result.message, result.cause, auth))
        }
    }

    companion object {
        /** A service reached over the tunnel lives on the VPS's own loopback. */
        const val DEFAULT_REMOTE_HOST = "127.0.0.1"

        /** Default VPS OpenCode port, matching the documented `opencode serve` port. */
        const val DEFAULT_REMOTE_PORT = 4096

        /** OpenCode's default basic-auth username. No password is used on the VPS in this step. */
        const val DEFAULT_OPENCODE_USERNAME = "opencode"

        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000L

        /** Stable runtime/connection id for a VPS profile, so selection survives restarts. */
        fun vpsProfileId(profile: SshProfile): String = "vps:${profile.id}"
    }
}
