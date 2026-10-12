package com.konprostart.tariffiacode.core.ssh

/**
 * An authenticated SSH session that outlives any single use.
 *
 * Separate from [SshPortForward] because the two have different costs. A session is a TCP connect, a
 * key exchange, an authentication round-trip and a keep-alive; a forward is one channel on top of it
 * that binds a loopback port in a millisecond. Opening a fresh session per tunnel meant the app held
 * several independent, equally expensive connections to the same host - the chat tunnel, the live
 * preview and every remote-git write - and every one of them paid the handshake again.
 *
 * Kept opaque on purpose: nothing outside `core/ssh` should need to know how a session is held.
 */
interface SshTunnelSession : AutoCloseable {
    /** True while the underlying SSH session is still open. */
    val isOpen: Boolean

    /** Closes the session and everything established on it. Idempotent. */
    override fun close()
}

/** Result of opening a reusable [SshTunnelSession]. */
sealed interface SshTunnelSessionResult {
    data class Opened(
        val session: SshTunnelSession,
    ) : SshTunnelSessionResult

    /** The server key is not yet trusted; surface it and let the user decide. */
    data class HostKeyUntrusted(
        val hostKey: SshHostKey,
    ) : SshTunnelSessionResult

    /** The session could not be established. */
    data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : SshTunnelSessionResult
}

/**
 * A [SshPortForwarder] that can also separate "connect" from "bind a port", so one session can carry
 * many forwards.
 *
 * A plain [SshPortForwarder] does both in one call, which is why it could not be pooled. Kept as a
 * separate interface rather than added to [SshPortForwarder] so test doubles of the latter keep
 * compiling and a caller that never shares can keep depending on the narrow one.
 */
interface SshSessionSharingForwarder : SshPortForwarder {
    /**
     * Connect and authenticate to [host]:[port] without binding a local port.
     *
     * Host keys are never auto-accepted: the same [verifier] policy as [openLocalForward] applies.
     * The caller owns the returned session and must close it.
     */
    suspend fun openSession(
        host: String,
        port: Int,
        auth: SshAuth,
        verifier: SshHostKeyVerifier,
        connectTimeoutMillis: Long = SshPortForwarder.DEFAULT_CONNECT_TIMEOUT_MILLIS,
    ): SshTunnelSessionResult

    /**
     * Bind a loopback port that forwards to [remoteHost]:[remotePort] as seen from the server,
     * over an existing [session]. The session is *borrowed*: closing the forward leaves it open.
     */
    suspend fun openForwardOver(
        session: SshTunnelSession,
        remoteHost: String,
        remotePort: Int,
    ): SshPortForwardResult
}
