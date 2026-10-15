package com.konprostart.tariffiacode.runtime.vps

import com.konprostart.tariffiacode.core.ssh.SshFailureDiagnostic
import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.core.ssh.SshPortForward
import com.konprostart.tariffiacode.core.ssh.SshPortForwardResult
import com.konprostart.tariffiacode.data.connection.ConnectionProfile
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.data.ssh.SshProfileHostKeyVerifier
import com.konprostart.tariffiacode.data.ssh.resolveAuth
import com.konprostart.tariffiacode.runtime.local.LocalRuntimeServerSecret

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

    /**
     * The tunnel is up and the OpenCode server answered, but it did not require the password.
     *
     * Distinct from [Failed] because the remedy is specific and actionable: the server has to be
     * started with `OPENCODE_SERVER_PASSWORD` set. It is also the outcome that stops this endpoint
     * from ever being handed out in the first place - see [VpsRuntimeConnector] for why an
     * unauthenticated VPS OpenCode API is not something the app should route a user's agent through.
     */
    data object OpenCodeUnauthenticated : VpsConnectOutcome

    /** The forward could not be established. */
    data class Failed(
        val message: String,
    ) : VpsConnectOutcome
}

/**
 * Establishes the SSH local port forward that connects TariffiaCode to an already-installed OpenCode HTTP
 * server on a VPS. Reuses the PR #27 pool transport and the PR #26 [SshCredentialStore]; it adds no
 * transport or credential logic of its own.
 *
 * The forward targets the VPS's own loopback ([DEFAULT_REMOTE_HOST]:[DEFAULT_REMOTE_PORT] by default),
 * because that is where `opencode serve` listens. The resulting loopback [ConnectionProfile] is consumed by
 * the existing remote OpenCode backend unchanged. The SSH session behind it is shared with any other
 * tunnel to the same profile (the live preview), so this costs a handshake only when none is open.
 *
 * **The tunneled endpoint is authenticated before it is handed out.**
 *
 * Android apps share `127.0.0.1`, so the local end of this forward - a loopback port with an ephemeral
 * number and no password - is reachable by every other app on the phone for as long as the tunnel is
 * up. An unauthenticated OpenCode server behind it is not just the user's own quota: it is a remote
 * shell, a remote filesystem and the user's provider keys, reachable by anything else installed. The
 * project already knows this; it is why the local runtime is protected by
 * [LocalRuntimeServerSecret]. The VPS path never got the same treatment and used to accept a server
 * with no password at all.
 *
 * So the profile's `OPENCODE_SERVER_PASSWORD` is required, sent as HTTP Basic auth on every request,
 * and - the part that actually closes the hole - checked: if the server answers a request that
 * carries no credentials, the endpoint is refused rather than returned. Refusing is the only
 * behaviour that helps, because forwarding to it would mean the app is knowingly handing an
 * unauthenticated endpoint to the user's agent.
 */
class VpsRuntimeConnector(
    private val pool: SshTunnelPool,
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
        val serverPassword = profile.opencodePassword?.takeIf { it.isNotBlank() }

        val verifier = SshProfileHostKeyVerifier(profile)
        return when (
            val result =
                pool.open(
                    key =
                        TunnelKey(
                            profileId = profile.id,
                            host = profile.host,
                            port = profile.port,
                            remoteHost = remoteHost,
                            remotePort = remotePort,
                        ),
                    auth = auth,
                    verifier = verifier,
                    connectTimeoutMillis = connectTimeoutMillis,
                )
        ) {
            is SshPortForwardResult.Listening -> {
                val forward = result.forward
                val target =
                    ConnectionProfile(
                        id = vpsProfileId(profile),
                        name = profile.name,
                        baseUrl = "http://${forward.localHost}:${forward.localPort}/",
                        username = opencodeUsername,
                        password = serverPassword,
                        allowInsecureLan = true,
                    )
                when (val authCheck = VpsOpenCodeAuth.verify(forward, opencodeUsername, serverPassword)) {
                    VpsOpenCodeAuth.Outcome.Authenticated ->
                        VpsConnectOutcome.Connected(target, forward)
                    // Both refusals close the forward. Returning it would leak the loopback listener
                    // for a runtime that is not going to use it.
                    VpsOpenCodeAuth.Outcome.Unauthenticated -> {
                        forward.close()
                        VpsConnectOutcome.OpenCodeUnauthenticated
                    }
                    is VpsOpenCodeAuth.Outcome.Failed -> {
                        forward.close()
                        VpsConnectOutcome.Failed(authCheck.message)
                    }
                }
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

        /** OpenCode's default basic-auth username. The password is the profile's own, required. */
        const val DEFAULT_OPENCODE_USERNAME = LocalRuntimeServerSecret.USERNAME

        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000L

        /** Stable runtime/connection id for a VPS profile, so selection survives restarts. */
        fun vpsProfileId(profile: SshProfile): String = "vps:${profile.id}"
    }
}
