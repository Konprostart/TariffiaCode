package com.konprostart.tariffiacode.feature.ssh

import com.konprostart.tariffiacode.core.ssh.SshAuth
import com.konprostart.tariffiacode.core.ssh.SshConnectResult
import com.konprostart.tariffiacode.core.ssh.SshConnectionClient
import com.konprostart.tariffiacode.core.ssh.SshFailureDiagnostic
import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.core.ssh.SshHostKeyDecision
import com.konprostart.tariffiacode.core.ssh.SshHostKeyVerifier
import com.konprostart.tariffiacode.core.ssh.SshSession
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshCredential
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile

/** Outcome of an attempted SSH connection, everything the UI needs to render a result. */
sealed interface SshConnectionOutcome {
    data class Connected(
        val session: SshSession,
    ) : SshConnectionOutcome

    /** The server presented a key the user has not trusted yet; ask before proceeding. */
    data class NeedsHostKeyTrust(
        val hostKey: SshHostKey,
    ) : SshConnectionOutcome

    /** The presented key does not match the trusted one; refuse and never offer to trust it. */
    data class HostKeyMismatch(
        val expectedFingerprint: String,
        val presentedFingerprint: String,
    ) : SshConnectionOutcome

    data class Failed(
        val message: String,
    ) : SshConnectionOutcome

    data object MissingCredential : SshConnectionOutcome
}

/**
 * Bridges a saved [SshProfile] and its stored [SshCredential] to the transport
 * [SshConnectionClient], implementing the strict host-key trust policy:
 *
 * - no trusted fingerprint on record → surface the presented key ([SshConnectionOutcome.NeedsHostKeyTrust]);
 * - a trusted fingerprint that matches → connect;
 * - a trusted fingerprint that differs → refuse ([SshConnectionOutcome.HostKeyMismatch]), never trust.
 *
 * Trust state is persisted by the caller via [persistTrustedFingerprint] once the user confirms.
 */
class SshConnectionManager(
    private val client: SshConnectionClient,
    private val credentials: SshCredentialStore,
) {
    /**
     * Attempt to connect using [profile] and its stored secret.
     *
     * @param connectTimeoutMillis transport timeout.
     */
    suspend fun connect(
        profile: SshProfile,
        connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    ): SshConnectionOutcome {
        val credential = credentials.credential(profile.credentialRef)
        if (credential == null || !credential.isUsable) return SshConnectionOutcome.MissingCredential

        val auth = buildAuth(profile, credential) ?: return SshConnectionOutcome.MissingCredential
        var mismatch: Pair<String, String>? = null

        val verifier =
            object : SshHostKeyVerifier {
                override fun verify(
                    host: String,
                    port: Int,
                    hostKey: SshHostKey,
                ): SshHostKeyDecision {
                    val trusted = profile.trustedHostKeyFingerprint
                    return when {
                        trusted.isNullOrBlank() -> SshHostKeyDecision.Unknown(hostKey)
                        trusted.equals(hostKey.sha256Fingerprint, ignoreCase = true) -> SshHostKeyDecision.Trusted
                        else -> {
                            mismatch = trusted to hostKey.sha256Fingerprint
                            // Reported as Unknown so the transport aborts; the UI maps it to a
                            // mismatch below rather than offering to trust the changed key.
                            SshHostKeyDecision.Unknown(hostKey)
                        }
                    }
                }
            }

        return when (
            val result =
                client.connect(
                    host = profile.host,
                    port = profile.port,
                    auth = auth,
                    verifier = verifier,
                    connectTimeoutMillis = connectTimeoutMillis,
                )
        ) {
            is SshConnectResult.Connected -> SshConnectionOutcome.Connected(result.session)
            is SshConnectResult.HostKeyUntrusted ->
                mismatch
                    ?.let { (expected, presented) -> SshConnectionOutcome.HostKeyMismatch(expected, presented) }
                    ?: SshConnectionOutcome.NeedsHostKeyTrust(result.hostKey)
            is SshConnectResult.Failure ->
                mismatch
                    ?.let { (expected, presented) -> SshConnectionOutcome.HostKeyMismatch(expected, presented) }
                    ?: SshConnectionOutcome.Failed(SshFailureDiagnostic.format(result.message, result.cause, auth))
        }
    }

    /** Persist the host key the user explicitly confirmed for [profile]. */
    fun trustHostKey(
        profile: SshProfile,
        hostKey: SshHostKey,
    ): SshProfile = profile.trusting(hostKey.sha256Fingerprint)

    private fun buildAuth(
        profile: SshProfile,
        credential: SshCredential,
    ): SshAuth? =
        when (credential) {
            is SshCredential.Password ->
                if (profile.authType == SshAuthType.PASSWORD) {
                    SshAuth.Password(username = profile.username, password = credential.password)
                } else {
                    null
                }
            is SshCredential.PrivateKey ->
                if (profile.authType == SshAuthType.PRIVATE_KEY) {
                    SshAuth.PrivateKey(
                        username = profile.username,
                        keyPem = credential.keyPem,
                        passphrase = credential.passphrase,
                    )
                } else {
                    null
                }
        }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000L
    }
}
