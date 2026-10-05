package com.konprostart.tariffiacode.feature.git

import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.data.ssh.SshProfile

/** Result of running one command over an SSH shell on the VPS. */
sealed interface RemoteCommandOutcome {
    data class Completed(
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
 * Runs a single shell script on the VPS over the existing SSH shell primitives (PR-E). Kept behind an
 * interface so the clone orchestration is testable without a real SSH server or git binary.
 */
interface RemoteGitCommandExecutor {
    suspend fun execute(
        profile: SshProfile,
        script: String,
        timeoutMillis: Long,
    ): RemoteCommandOutcome
}
