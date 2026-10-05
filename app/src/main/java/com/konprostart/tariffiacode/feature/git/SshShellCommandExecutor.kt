package com.konprostart.tariffiacode.feature.git

import com.konprostart.tariffiacode.core.ssh.SshSession
import com.konprostart.tariffiacode.core.ssh.SshShellClient
import com.konprostart.tariffiacode.core.ssh.SshShellResult
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.data.ssh.SshProfileHostKeyVerifier
import com.konprostart.tariffiacode.data.ssh.resolveAuth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs a script over an interactive SSH shell ([SshShellClient]) and collects its output until the script
 * prints a completion marker. This reuses the existing SSH shell primitive; it adds no exec/SFTP channel
 * and does not change the transport.
 */
class SshShellCommandExecutor(
    private val shellClient: SshShellClient,
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
            val opened =
                shellClient.openShell(
                    host = profile.host,
                    port = profile.port,
                    auth = auth,
                    verifier = verifier,
                )
        ) {
            is SshShellResult.HostKeyUntrusted -> RemoteCommandOutcome.HostKeyUntrusted(opened.hostKey)
            is SshShellResult.Failure -> RemoteCommandOutcome.Failed(opened.message)
            is SshShellResult.Opened -> {
                val session = opened.session
                try {
                    RemoteCommandOutcome.Completed(runScript(session, script, timeoutMillis))
                } finally {
                    session.close()
                }
            }
        }
    }

    private suspend fun runScript(
        session: SshSession,
        script: String,
        timeoutMillis: Long,
    ): String {
        val collected = StringBuilder()
        session.writeStandardInput((script + "\n").toByteArray())
        withTimeoutOrNull(timeoutMillis) {
            session.output.first { bytes ->
                collected.append(bytes.decodeToString())
                collected.contains(EXIT_MARKER) || collected.contains(NONEMPTY_MARKER)
            }
        }
        return collected.toString()
    }

    companion object {
        const val EXIT_MARKER = "__TC_EXIT__"
        const val NONEMPTY_MARKER = "__TC_NONEMPTY__"
    }
}
