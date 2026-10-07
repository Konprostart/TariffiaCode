package com.konprostart.tariffiacode.core.ssh

/**
 * Runs a single command over a one-shot SSH "exec" channel and returns the remote process's own exit
 * status.
 *
 * Unlike [SshShellClient] (a long-lived interactive PTY, where stdout/stderr are merged and there is
 * no per-command exit status), an exec channel gets a real `exit-status` message from the SSH server
 * when the command finishes. That is the reliable control channel: the exit code is delivered out of
 * band from stdout/stderr, so nothing a command prints can forge it.
 */
interface SshExecClient {
    /**
     * Connect to [host]:[port], authenticate with [auth], enforce host-key verification through
     * [verifier], then run [command] on an exec channel and wait up to [timeoutMillis] for it to
     * finish.
     */
    suspend fun exec(
        host: String,
        port: Int = DEFAULT_SSH_PORT,
        auth: SshAuth,
        verifier: SshHostKeyVerifier,
        command: String,
        timeoutMillis: Long,
        connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    ): SshExecResult

    companion object {
        const val DEFAULT_SSH_PORT = 22
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000L
    }
}

/** Result of running one command over an SSH exec channel. */
sealed interface SshExecResult {
    /**
     * The command finished. [exitCode] is the remote process's own exit status, received through the
     * SSH `exit-status` message - not parsed from [stdout]/[stderr].
     */
    data class Completed(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) : SshExecResult

    /** The server key is not yet trusted; surface it and let the user decide, as elsewhere. */
    data class HostKeyUntrusted(
        val hostKey: SshHostKey,
    ) : SshExecResult

    /** The command could not be run, timed out, or the server did not report an exit status. */
    data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : SshExecResult
}
