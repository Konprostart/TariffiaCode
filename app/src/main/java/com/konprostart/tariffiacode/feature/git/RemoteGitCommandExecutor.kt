package com.konprostart.tariffiacode.feature.git

import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.data.ssh.SshProfile

/** Result of running one command on the VPS. */
sealed interface RemoteCommandOutcome {
    /**
     * The command finished. [exitCode] is the remote process's own exit status from the SSH
     * `exit-status` control channel, not something parsed out of [output].
     */
    data class Completed(
        val exitCode: Int,
        val output: String,
    ) : RemoteCommandOutcome

    data object MissingCredential : RemoteCommandOutcome

    data class HostKeyUntrusted(
        val hostKey: SshHostKey,
    ) : RemoteCommandOutcome

    data class Failed(
        val message: String,
    ) : RemoteCommandOutcome
}

/**
 * Runs a single command on the VPS over an SSH exec channel ([com.konprostart.tariffiacode.core.ssh.SshExecClient]),
 * whose `exit-status` message is the reliable source of the exit code. Kept behind an interface so the
 * clone orchestration is testable without a real SSH server or git binary.
 */
interface RemoteGitCommandExecutor {
    suspend fun execute(
        profile: SshProfile,
        script: String,
        timeoutMillis: Long,
    ): RemoteCommandOutcome
}
