package com.konprostart.tariffiacode.core.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.client.channel.ChannelExec
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.session.ClientSession
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.EnumSet
import org.apache.sshd.client.SshClient as MinaApacheSshClient

/**
 * Apache MINA SSHD backed [SshExecClient]. Pure-JVM and Android-compatible, isolated behind the
 * [SshExecClient] interface so nothing else depends on MINA types.
 *
 * The exit status is read from [ChannelExec.getExitStatus], which MINA fills from the SSH
 * `exit-status` message the server sends when the command finishes. It is never derived from the
 * captured output, so a command that prints a marker-like string cannot change its own exit code.
 */
class MinaSshExecClient(
    private val clientFactory: () -> MinaApacheSshClient = { MinaApacheSshClient.setUpDefaultClient() },
) : SshExecClient {
    override suspend fun exec(
        host: String,
        port: Int,
        auth: SshAuth,
        verifier: SshHostKeyVerifier,
        command: String,
        timeoutMillis: Long,
        connectTimeoutMillis: Long,
    ): SshExecResult =
        withContext(Dispatchers.IO) {
            val client = clientFactory()
            val hostKeyVerifier = FingerprintServerKeyVerifier(host, port, verifier)
            client.serverKeyVerifier = hostKeyVerifier
            var session: ClientSession? = null
            var channel: ChannelExec? = null
            try {
                client.start()
                session =
                    client.connect(auth.username, host, port)
                        .verify(connectTimeoutMillis)
                        .session

                when (auth) {
                    is SshAuth.Password -> session.addPasswordIdentity(auth.password)
                    is SshAuth.PrivateKey -> session.addPublicKeyIdentity(SshKeyPairs.load(auth))
                }
                session.auth().verify(AUTH_TIMEOUT_MILLIS)

                val stdout = ByteArrayOutputStream()
                val stderr = ByteArrayOutputStream()
                channel = session.createExecChannel(command)
                channel.setOut(stdout)
                channel.setErr(stderr)
                channel.open().verify(connectTimeoutMillis)

                val events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), timeoutMillis)
                if (ClientChannelEvent.TIMEOUT in events) {
                    SshExecResult.Failure("Remote command timed out")
                } else {
                    val exit = channel.exitStatus
                    if (exit == null) {
                        SshExecResult.Failure("Remote command did not report an exit status")
                    } else {
                        SshExecResult.Completed(
                            exitCode = exit,
                            stdout = stdout.toString(StandardCharsets.UTF_8.name()),
                            stderr = stderr.toString(StandardCharsets.UTF_8.name()),
                        )
                    }
                }
            } catch (e: Exception) {
                val untrusted = hostKeyVerifier.untrustedKey
                if (untrusted != null) {
                    SshExecResult.HostKeyUntrusted(untrusted)
                } else {
                    SshExecResult.Failure(e.message ?: "SSH command failed", e)
                }
            } finally {
                runCatching { channel?.close(false) }
                runCatching { session?.close(false) }
                runCatching { client.stop() }
            }
        }

    private companion object {
        const val AUTH_TIMEOUT_MILLIS = 30_000L
    }
}
