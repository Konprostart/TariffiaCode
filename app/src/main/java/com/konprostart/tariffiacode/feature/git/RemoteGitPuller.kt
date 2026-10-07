package com.konprostart.tariffiacode.feature.git

import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.data.remote.RemoteGitPath
import com.konprostart.tariffiacode.data.ssh.SshProfile

/** Outcome of a remote `git pull`. */
sealed interface RemoteGitPullResult {
    data class Success(
        val output: String,
    ) : RemoteGitPullResult

    data class Invalid(
        val errors: Map<String, String>,
    ) : RemoteGitPullResult

    data object MissingCredential : RemoteGitPullResult

    data class HostKeyUntrusted(
        val hostKey: SshHostKey,
    ) : RemoteGitPullResult

    data class Failed(
        val message: String,
        val output: String = "",
    ) : RemoteGitPullResult
}

/**
 * Refreshes an already-cloned Remote Project with `git pull --ff-only`, using the existing SSH shell
 * execution ([RemoteGitCommandExecutor] from the clone workflow). It validates and quotes the mapped
 * remote path, refuses a directory that is not a git repository, and reports the git exit status. No new
 * Git system, no SFTP/SCP, no transport change.
 */
class RemoteGitPuller(
    private val executor: RemoteGitCommandExecutor,
) {
    suspend fun pull(
        profile: SshProfile,
        remotePath: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): RemoteGitPullResult {
        val error = RemoteGitPath.validate(remotePath)
        if (error != null) return RemoteGitPullResult.Invalid(mapOf(RemoteGitPath.FIELD_PATH to error))

        return when (val outcome = executor.execute(profile, buildScript(remotePath), timeoutMillis)) {
            RemoteCommandOutcome.MissingCredential -> RemoteGitPullResult.MissingCredential
            is RemoteCommandOutcome.HostKeyUntrusted -> RemoteGitPullResult.HostKeyUntrusted(outcome.hostKey)
            is RemoteCommandOutcome.Failed -> RemoteGitPullResult.Failed(outcome.message)
            is RemoteCommandOutcome.Completed -> parse(outcome)
        }
    }

    private fun buildScript(remotePath: String): String {
        val path = RemoteGitPath.shellQuote(remotePath.trim())
        return "if [ ! -d $path/.git ]; then echo \"Not a git repository: $path\" >&2; exit 128; " +
            "else git -C $path pull --ff-only 2>&1; fi"
    }

    private fun parse(outcome: RemoteCommandOutcome.Completed): RemoteGitPullResult =
        if (outcome.exitCode == 0) {
            RemoteGitPullResult.Success(outcome.output.trim())
        } else {
            RemoteGitPullResult.Failed("git pull failed (exit ${outcome.exitCode})", outcome.output)
        }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 300_000L
    }
}
