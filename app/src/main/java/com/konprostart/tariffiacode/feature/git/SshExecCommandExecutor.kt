package com.konprostart.tariffiacode.feature.git

import com.konprostart.tariffiacode.core.ssh.SshExecClient
import com.konprostart.tariffiacode.core.ssh.SshExecResult
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.data.ssh.SshProfileHostKeyVerifier
import com.konprostart.tariffiacode.data.ssh.resolveAuth

/**
 * Runs a command on the VPS over an SSH exec channel ([SshExecClient]).
 *
 * The exit code comes from the SSH `exit-status` control channel, so a command's stdout/stderr can
 * never forge it - unlike the previous approach, which appended a fixed `__TC_EXIT__$?` marker to the
 * script and parsed the first marker out of the merged PTY output.
 */
class SshExecCommandExecutor(
    private val execClient: SshExecClient,
    private val credentials: SshCredentialStore,
) : RemoteGitCommandExecutor {
    override suspend fun execute(
        profile: SshProfile,
        script: String,
        timeoutMillis: Long,
    ): RemoteCommandOutcome {
        val credential = credentials.credential(profile.credentialRef)
        if (credential == null || !credential.isUsable) return RemoteCommandOutcome.MissingCredential
        val auth = profile.resolveAuth(credential) ?: return RemoteCommandOutcome.MissingCredential

        val verifier = SshProfileHostKeyVerifier(profile)
        return when (
            val result =
                execClient.exec(
                    host = profile.host,
                    port = profile.port,
                    auth = auth,
                    verifier = verifier,
                    command = script,
                    timeoutMillis = timeoutMillis,
                )
        ) {
            is SshExecResult.HostKeyUntrusted -> RemoteCommandOutcome.HostKeyUntrusted(result.hostKey)
            is SshExecResult.Failure -> RemoteCommandOutcome.Failed(result.message)
            is SshExecResult.Completed ->
                RemoteCommandOutcome.Completed(
                    exitCode = result.exitCode,
                    // Git writes its progress/errors to stderr; keep both so the UI shows what happened.
                    output = (result.stdout + result.stderr).trimEnd(),
                )
        }
    }
}
