package com.konprostart.tariffiacode.feature.git

import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.data.remote.RemoteGitPath
import com.konprostart.tariffiacode.data.ssh.SshProfile

/** Outcome of a remote `git push`. */
sealed interface RemoteGitPushResult {
    data class Success(
        val output: String,
    ) : RemoteGitPushResult

    data class Invalid(
        val errors: Map<String, String>,
    ) : RemoteGitPushResult

    data object MissingCredential : RemoteGitPushResult

    data class HostKeyUntrusted(
        val hostKey: SshHostKey,
    ) : RemoteGitPushResult

    data class Failed(
        val message: String,
        val output: String = "",
    ) : RemoteGitPushResult
}

/**
 * Pushes the mapped Remote Project with `git push`, using the existing SSH shell execution
 * ([RemoteGitCommandExecutor] from the clone workflow). It validates and quotes the mapped remote path,
 * refuses a directory that is not a git repository, and reports the git exit status. Plain `push` only —
 * no force-push, no branch management, no GitHub PR features. No new Git system, no SFTP/SCP, no transport
 * change.
 */
class RemoteGitPusher(
    private val executor: RemoteGitCommandExecutor,
) {
    suspend fun push(
        profile: SshProfile,
        remotePath: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): RemoteGitPushResult {
        val error = RemoteGitPath.validate(remotePath)
        if (error != null) return RemoteGitPushResult.Invalid(mapOf(RemoteGitPath.FIELD_PATH to error))

        return when (val outcome = executor.execute(profile, buildScript(remotePath), timeoutMillis)) {
            RemoteCommandOutcome.MissingCredential -> RemoteGitPushResult.MissingCredential
            is RemoteCommandOutcome.HostKeyUntrusted -> RemoteGitPushResult.HostKeyUntrusted(outcome.hostKey)
            is RemoteCommandOutcome.Failed -> RemoteGitPushResult.Failed(outcome.message)
            is RemoteCommandOutcome.Completed -> parse(outcome)
        }
    }

    private fun buildScript(remotePath: String): String {
        val path = RemoteGitPath.shellQuote(remotePath.trim())
        return "if [ ! -d $path/.git ]; then echo \"Not a git repository: $path\" >&2; exit 128; " +
            "else git -C $path push 2>&1; fi"
    }

    private fun parse(outcome: RemoteCommandOutcome.Completed): RemoteGitPushResult =
        if (outcome.exitCode == 0) {
            RemoteGitPushResult.Success(outcome.output.trim())
        } else {
            RemoteGitPushResult.Failed("git push failed (exit ${outcome.exitCode})", outcome.output)
        }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 300_000L
    }
}
