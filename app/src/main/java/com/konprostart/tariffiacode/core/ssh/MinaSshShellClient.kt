package com.konprostart.tariffiacode.core.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.session.ClientSession
import java.io.PipedInputStream
import java.io.PipedOutputStream
import org.apache.sshd.client.SshClient as MinaApacheSshClient

/**
 * Apache MINA SSHD backed [SshShellClient]. Pure-JVM and Android-compatible, isolated behind the
 * [SshShellClient] interface so nothing else depends on MINA types.
 *
 * Unlike the one-shot transport, this class keeps the [MinaApacheSshClient] and [ClientSession] alive for
 * as long as the returned [SshSession] is open, and releases them when it is closed. Host keys are never
 * auto-accepted: the same [FingerprintServerKeyVerifier] policy is used.
 */
class MinaSshShellClient(
    private val clientFactory: () -> MinaApacheSshClient = { MinaApacheSshClient.setUpDefaultClient() },
) : SshShellClient {
    override suspend fun openShell(
        host: String,
        port: Int,
        auth: SshAuth,
        verifier: SshHostKeyVerifier,
        ptyType: String,
        connectTimeoutMillis: Long,
    ): SshShellResult =
        withContext(Dispatchers.IO) {
            val client = clientFactory()
            val hostKeyVerifier = FingerprintServerKeyVerifier(host, port, verifier)
            client.serverKeyVerifier = hostKeyVerifier
            var session: ClientSession? = null
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

                val channel = session.createShellChannel()
                channel.setUsePty(true)
                channel.ptyType = ptyType
                channel.setEnv("TERM", ptyType)

                val shell = MinaOwnedShellSession(client, session, channel)
                channel.setIn(shell.remoteInput)
                channel.setOut(shell.remoteOutput)
                channel.setErr(shell.remoteError)
                channel.open().verify(connectTimeoutMillis)

                SshShellResult.Opened(shell)
            } catch (e: Exception) {
                runCatching { session?.close(false) }
                runCatching { client.stop() }
                val untrusted = hostKeyVerifier.untrustedKey
                if (untrusted != null) {
                    SshShellResult.HostKeyUntrusted(untrusted)
                } else {
                    SshShellResult.Failure(e.message ?: "SSH shell failed", e)
                }
            }
        }

    private companion object {
        const val AUTH_TIMEOUT_MILLIS = 30_000L
    }
}

/**
 * A live shell owned by [MinaSshShellClient]. Closing it closes the channel, the SSH session and the
 * client that carries them, so no SSH resources are leaked.
 *
 * Remote stdout/stderr are written by MINA into the [PipedOutputStream]s handed to `setOut`/`setErr`; the
 * paired [PipedInputStream]s are drained here. stdin is the opposite direction: bytes written to
 * [writeStandardInput] go into a pipe whose read side MINA consumes via `setIn`.
 */
private class MinaOwnedShellSession(
    private val client: MinaApacheSshClient,
    private val session: ClientSession,
    private val channel: ChannelShell,
) : SshSession {
    private val stdoutPipe = PipedOutputStream()
    private val stderrPipe = PipedOutputStream()
    private val stdinPipe = PipedOutputStream()

    val remoteInput: PipedInputStream = PipedInputStream(stdinPipe, PIPE_BUFFER_SIZE)
    val remoteOutput: PipedOutputStream = stdoutPipe
    val remoteError: PipedOutputStream = stderrPipe

    private val stdout: PipedInputStream = PipedInputStream(stdoutPipe, PIPE_BUFFER_SIZE)
    private val stderr: PipedInputStream = PipedInputStream(stderrPipe, PIPE_BUFFER_SIZE)

    @Volatile
    private var open = true

    override val output: Flow<ByteArray> =
        channelFlow {
            val buffer = ByteArray(PIPE_BUFFER_SIZE)
            try {
                while (open) {
                    var readAny = false
                    if (stdout.available() > 0) {
                        val n = stdout.read(buffer)
                        if (n > 0) {
                            trySend(buffer.copyOf(n))
                            readAny = true
                        }
                    }
                    if (stderr.available() > 0) {
                        val n = stderr.read(buffer)
                        if (n > 0) {
                            trySend(buffer.copyOf(n))
                            readAny = true
                        }
                    }
                    if (!readAny) delay(POLL_INTERVAL_MILLIS)
                }
            } finally {
                close()
            }
            awaitClose { close() }
        }

    override fun writeStandardInput(bytes: ByteArray) {
        if (!open) return
        stdinPipe.write(bytes)
        stdinPipe.flush()
    }

    override fun resize(
        columns: Int,
        rows: Int,
    ) {
        runCatching { channel.sendWindowChange(columns, rows) }
    }

    override val isOpen: Boolean
        get() = open

    @Synchronized
    override fun close() {
        if (!open) return
        open = false
        runCatching { stdinPipe.close() }
        runCatching { stdoutPipe.close() }
        runCatching { stderrPipe.close() }
        runCatching { channel.close(false) }
        runCatching { session.close(false) }
        runCatching { client.stop() }
    }

    private companion object {
        const val PIPE_BUFFER_SIZE = 8192
        const val POLL_INTERVAL_MILLIS = 20L
    }
}
