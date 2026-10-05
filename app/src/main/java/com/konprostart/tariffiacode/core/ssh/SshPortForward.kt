package com.konprostart.tariffiacode.core.ssh

/**
 * A live SSH local TCP port forward.
 *
 * A connection accepted on [localHost]:[localPort] is tunnelled over SSH to [remoteHost]:[remotePort] as
 * seen from the SSH server. This is the transport primitive used to reach a service that is bound to the
 * server's own loopback (for example an OpenCode HTTP server listening on 127.0.0.1:4096). The forward is
 * bound to the SSH session that created it: closing the session closes the forward, and [close] closes the
 * local listener and tears the sessions down. [close] is idempotent.
 */
interface SshPortForward : AutoCloseable {
    /** Local interface the listener is bound to (always loopback). */
    val localHost: String

    /** Actual bound local port. When an ephemeral port (0) was requested this is the port the OS assigned. */
    val localPort: Int

    /** Remote host the tunnel targets, as seen from the SSH server. */
    val remoteHost: String

    /** Remote port the tunnel targets. */
    val remotePort: Int

    /** True while the underlying SSH session and local listener are still open. */
    val isOpen: Boolean

    /** Stop forwarding and close the listener and its SSH session. Idempotent. */
    override fun close()
}

/** Result of attempting to open a local port forward. */
sealed interface SshPortForwardResult {
    /** The forward is running; [forward] must eventually be closed. */
    data class Listening(
        val forward: SshPortForward,
    ) : SshPortForwardResult

    /** The server key is not yet trusted; surface it and let the user decide, as with a shell connect. */
    data class HostKeyUntrusted(
        val hostKey: SshHostKey,
    ) : SshPortForwardResult

    /** The forward could not be established. */
    data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : SshPortForwardResult
}

/**
 * Opens SSH local TCP port forwards. Implemented by [MinaSshPortForwarder] on top of Apache MINA SSHD.
 *
 * Small on purpose: open → hold a loopback listener → close. It deliberately does not depend on
 * [SshConnectionClient] so a forward can be established without opening an interactive shell.
 */
interface SshPortForwarder {
    /**
     * Connect to [host]:[port], authenticate with [auth], enforce host-key verification through
     * [verifier], then listen on a loopback ephemeral port and forward to
     * [remoteHost]:[remotePort] as seen from the server.
     *
     * Cancellable and timeout-bounded. The returned [SshPortForward] owns the SSH session until closed.
     */
    suspend fun openLocalForward(
        host: String,
        port: Int = DEFAULT_SSH_PORT,
        auth: SshAuth,
        verifier: SshHostKeyVerifier,
        remoteHost: String = DEFAULT_REMOTE_HOST,
        remotePort: Int = DEFAULT_REMOTE_PORT,
        connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    ): SshPortForwardResult

    companion object {
        const val DEFAULT_SSH_PORT = 22

        /** A service on the server reached over the tunnel is normally on the server's own loopback. */
        const val DEFAULT_REMOTE_HOST = "127.0.0.1"

        /** Default remote port for an OpenCode server, matching the documented `opencode serve` port. */
        const val DEFAULT_REMOTE_PORT = 4096

        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000L
    }
}
