package com.konprostart.tariffiacode.core.ssh

/**
 * SSH foundation models. These are transport-only: no VPS/agent/git concepts live here. The client is
 * deliberately isolated behind [SshClient] so a later Terminal/VPS feature can consume a live PTY
 * without coupling to a specific SSH library.
 */

/** How to authenticate an SSH session. Secrets are passed in but never logged by this layer. */
sealed interface SshAuth {
    /** Username + password. */
    data class Password(
        val username: String,
        val password: String,
    ) : SshAuth

    /**
     * Username + an imported/generated private key.
     *
     * @param keyPem the private key in PEM (OpenSSH or PKCS#8) form; never logged.
     * @param passphrase optional passphrase for the key; never logged.
     */
    data class PrivateKey(
        val username: String,
        val keyPem: String,
        val passphrase: String? = null,
    ) : SshAuth
}

/** The login username carried by any [SshAuth]. */
val SshAuth.username: String
    get() =
        when (this) {
            is SshAuth.Password -> username
            is SshAuth.PrivateKey -> username
        }

/** A server's public host key as seen by the client, identified by its SHA-256 fingerprint. */
data class SshHostKey(
    /** e.g. "ssh-ed25519" or "rsa-sha2-512". */
    val keyType: String,
    /** Lowercase hex SHA-256 of the raw host-key blob (no "SHA256:" prefix, no padding). */
    val sha256Fingerprint: String,
)

/** Outcome of host-key verification. */
sealed interface SshHostKeyDecision {
    /** The fingerprint matches a previously trusted one. */
    data object Trusted : SshHostKeyDecision

    /** The fingerprint is unknown; the caller must show it and let the user explicitly trust it. */
    data class Unknown(
        val hostKey: SshHostKey,
    ) : SshHostKeyDecision
}

/**
 * Host-key verification contract. Implementations must NEVER accept all keys: they compare the presented
 * key against the set of fingerprints the user has explicitly trusted for the host.
 */
interface SshHostKeyVerifier {
    /**
     * Called during the handshake with the server's host key.
     *
     * @return [SshHostKeyDecision.Trusted] to proceed, or [SshHostKeyDecision.Unknown] to abort so the
     *   UI can present the fingerprint for explicit trust. Implementations that can mutate trust state
     *   (persist on approval) implement [SshTrustStore].
     */
    fun verify(
        host: String,
        port: Int,
        hostKey: SshHostKey,
    ): SshHostKeyDecision
}

/** A verifier that can persist an explicitly user-trusted fingerprint (UI approval path). */
interface SshTrustStore : SshHostKeyVerifier {
    /** Record that the user explicitly trusted [hostKey] for [host]:[port]. */
    fun trust(
        host: String,
        port: Int,
        hostKey: SshHostKey,
    )
}

/** A live SSH channel carrying an interactive shell/PTY. */
interface SshSession : AutoCloseable {
    /** Write raw bytes to the remote stdin (e.g. typed characters). */
    fun writeStandardInput(bytes: ByteArray)

    /** Allocate/resize the remote PTY to the given character-cell dimensions. */
    fun resize(
        columns: Int,
        rows: Int,
    )

    /**
     * Standard output bytes as they arrive. Completes when the channel closes. Errors are surfaced
     * through [SshConnectResult.Failure] at connect time and through [isOpen] afterwards; the flow
     * itself only carries stdout/stderr merged by the PTY (a PTY has a single stream).
     */
    val output: kotlinx.coroutines.flow.Flow<ByteArray>

    /** True while the underlying channel is open. */
    val isOpen: Boolean

    /** Close the channel/connection and release resources. Idempotent. */
    override fun close()
}

/** Result of connecting + authenticating. */
sealed interface SshConnectResult {
    data class Connected(
        val session: SshSession,
    ) : SshConnectResult

    /** Host key not yet trusted; surface [SshHostKeyDecision.Unknown.hostKey] and let the user decide. */
    data class HostKeyUntrusted(
        val hostKey: SshHostKey,
    ) : SshConnectResult

    data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : SshConnectResult
}

/**
 * The single seam the rest of the app depends on. Implemented by [MinaSshClient]. Small on purpose:
 * connect → open an interactive shell → stream I/O → close.
 */
interface SshClient {
    /**
     * Connect to [host]:[port] and authenticate with [auth], enforcing host-key verification through
     * [verifier]. Cancellable and timeout-bounded.
     */
    suspend fun connect(
        host: String,
        port: Int = 22,
        auth: SshAuth,
        verifier: SshHostKeyVerifier,
        connectTimeoutMillis: Long = 15_000,
    ): SshConnectResult
}
