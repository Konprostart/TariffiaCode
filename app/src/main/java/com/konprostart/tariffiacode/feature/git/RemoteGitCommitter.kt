package com.konprostart.tariffiacode.feature.git

import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.data.remote.RemoteGitPath
import com.konprostart.tariffiacode.data.ssh.SshProfile

/** Outcome of a remote `git add -A && git commit`. */
sealed interface RemoteGitCommitResult {
    data class Success(
        val output: String,
    ) : RemoteGitCommitResult

    data class Invalid(
        val errors: Map<String, String>,
    ) : RemoteGitCommitResult

    data object MissingCredential : RemoteGitCommitResult

    data class HostKeyUntrusted(
        val hostKey: SshHostKey,
    ) : RemoteGitCommitResult

    data class Failed(
        val message: String,
        val output: String = "",
    ) : RemoteGitCommitResult
}

/**
 * Commits the mapped Remote Project with `git add -A && git commit -m <message>`, using the existing SSH
 * exec execution ([RemoteGitCommandExecutor] from the clone workflow). It validates and single-quotes both
 * the mapped remote path and the commit message, refuses a directory that is not a git repository, and
 * reports the git exit status. Stage-everything plus commit on the current branch only — no amend, no
 * force, no branch management, no push (that is [RemoteGitPusher]). No new Git system, no transport change.
 */
class RemoteGitCommitter(
    private val executor: RemoteGitCommandExecutor,
) {
    suspend fun commit(
        profile: SshProfile,
        remotePath: String,
        message: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): RemoteGitCommitResult {
        val pathError = RemoteGitPath.validate(remotePath)
        if (pathError != null) return RemoteGitCommitResult.Invalid(mapOf(RemoteGitPath.FIELD_PATH to pathError))
        if (message.isBlank()) return RemoteGitCommitResult.Invalid(mapOf(FIELD_MESSAGE to "missing"))

        return when (val outcome = executor.execute(profile, buildScript(remotePath, message), timeoutMillis)) {
            RemoteCommandOutcome.MissingCredential -> RemoteGitCommitResult.MissingCredential
            is RemoteCommandOutcome.HostKeyUntrusted -> RemoteGitCommitResult.HostKeyUntrusted(outcome.hostKey)
            is RemoteCommandOutcome.Failed -> RemoteGitCommitResult.Failed(outcome.message)
            is RemoteCommandOutcome.Completed -> parse(outcome)
        }
    }

    private fun buildScript(
        remotePath: String,
        message: String,
    ): String {
        val path = RemoteGitPath.shellQuote(remotePath.trim())
        val quotedMessage = RemoteGitPath.shellQuote(message.trim())
        return "if [ ! -d $path/.git ]; then echo \"Not a git repository: $path\" >&2; exit 128; " +
            "else git -C $path add -A && git -C $path commit -m $quotedMessage 2>&1; fi"
    }

    private fun parse(outcome: RemoteCommandOutcome.Completed): RemoteGitCommitResult =
        if (outcome.exitCode == 0) {
            RemoteGitCommitResult.Success(outcome.output.trim())
        } else {
            RemoteGitCommitResult.Failed("git commit failed (exit ${outcome.exitCode})", outcome.output)
        }

    companion object {
        /** Field key for the commit message in [RemoteGitCommitResult.Invalid]. */
        const val FIELD_MESSAGE = "commitMessage"

        const val DEFAULT_TIMEOUT_MILLIS = 300_000L
    }
}
