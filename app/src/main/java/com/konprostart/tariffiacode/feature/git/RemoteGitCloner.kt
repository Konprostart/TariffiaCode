package com.konprostart.tariffiacode.feature.git

import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.data.remote.RemoteGitCloneRequest
import com.konprostart.tariffiacode.data.remote.RemoteGitCloneValidator
import com.konprostart.tariffiacode.data.ssh.SshProfile

/** Outcome of a remote `git clone`. */
sealed interface RemoteGitCloneResult {
    data class Success(
        val output: String,
    ) : RemoteGitCloneResult

    data class Invalid(
        val errors: Map<String, String>,
    ) : RemoteGitCloneResult

    /** The target path already exists and is not empty; refusing to clone into it. */
    data object TargetNotEmpty : RemoteGitCloneResult

    data object MissingCredential : RemoteGitCloneResult

    data class HostKeyUntrusted(
        val hostKey: SshHostKey,
    ) : RemoteGitCloneResult

    data class Failed(
        val message: String,
        val output: String = "",
    ) : RemoteGitCloneResult
}

/**
 * Clones a Git repository onto the VPS at the requested remote path, using the existing SSH shell
 * execution ([RemoteGitCommandExecutor]). It builds one POSIX shell script that (a) refuses a non-empty
 * target and (b) runs `git clone`, then reports the exit status. No new Git system and no SFTP/SCP.
 */
class RemoteGitCloner(
    private val executor: RemoteGitCommandExecutor,
) {
    suspend fun clone(
        profile: SshProfile,
        request: RemoteGitCloneRequest,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): RemoteGitCloneResult {
        val errors = RemoteGitCloneValidator.validate(request)
        if (errors.isNotEmpty()) return RemoteGitCloneResult.Invalid(errors)

        return when (val outcome = executor.execute(profile, buildScript(request), timeoutMillis)) {
            RemoteCommandOutcome.MissingCredential -> RemoteGitCloneResult.MissingCredential
            is RemoteCommandOutcome.HostKeyUntrusted -> RemoteGitCloneResult.HostKeyUntrusted(outcome.hostKey)
            is RemoteCommandOutcome.Failed -> RemoteGitCloneResult.Failed(outcome.message)
            is RemoteCommandOutcome.Completed -> parse(outcome)
        }
    }

    private fun buildScript(request: RemoteGitCloneRequest): String {
        val url = RemoteGitCloneValidator.shellQuote(request.repositoryUrl.trim())
        val path = RemoteGitCloneValidator.shellQuote(request.remotePath.trim())
        val dollar = "$"
        // Exit 3 is this script's own signal for "target exists and is not empty"; any other status is
        // git's. The status travels on the SSH control channel, never in the command's output.
        return "if [ -e $path ] && [ -n \"$dollar(ls -A $path 2>/dev/null)\" ]; then exit $TARGET_NOT_EMPTY_EXIT; " +
            "else git clone --depth 1 $url $path 2>&1; fi"
    }

    private fun parse(outcome: RemoteCommandOutcome.Completed): RemoteGitCloneResult =
        when (outcome.exitCode) {
            TARGET_NOT_EMPTY_EXIT -> RemoteGitCloneResult.TargetNotEmpty
            0 -> RemoteGitCloneResult.Success(outcome.output.trim())
            else -> RemoteGitCloneResult.Failed("git clone failed (exit ${outcome.exitCode})", outcome.output)
        }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 300_000L

        /** Script exit status meaning "target exists and is not empty". */
        const val TARGET_NOT_EMPTY_EXIT = 3
    }
}
