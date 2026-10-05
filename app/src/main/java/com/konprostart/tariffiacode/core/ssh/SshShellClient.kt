package com.konprostart.tariffiacode.core.ssh

/**
 * Opens a long-lived interactive shell (PTY) over SSH.
 *
 * This is the additive sibling of [SshConnectionClient]: where the one-shot connect is designed to be
 * consumed immediately, this entry point owns the SSH client for the whole life of the returned
 * [SshSession], so a terminal can stream output and send input until it is closed. It reuses the same
 * [SshAuth], [SshHostKeyVerifier] and [SshSession] primitives and does not change them.
 */
interface SshShellClient {
    /**
     * Connect to [host]:[port], authenticate with [auth], enforce host-key verification through
     * [verifier], then open an interactive PTY shell.
     *
     * The returned [SshSession] owns the SSH client/session until [SshSession.close]; the caller must
     * close it to release the resources.
     */
    suspend fun openShell(
        host: String,
        port: Int = DEFAULT_SSH_PORT,
        auth: SshAuth,
        verifier: SshHostKeyVerifier,
        ptyType: String = DEFAULT_PTY_TYPE,
        connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    ): SshShellResult

    companion object {
        const val DEFAULT_SSH_PORT = 22
        const val DEFAULT_PTY_TYPE = "xterm-256color"
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000L
    }
}

/** Result of attempting to open an interactive shell. */
sealed interface SshShellResult {
    /** The shell is running; [session] must be closed by the caller. */
    data class Opened(
        val session: SshSession,
    ) : SshShellResult

    /** The server key is not yet trusted; surface it and let the user decide, as elsewhere. */
    data class HostKeyUntrusted(
        val hostKey: SshHostKey,
    ) : SshShellResult

    data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : SshShellResult
}
