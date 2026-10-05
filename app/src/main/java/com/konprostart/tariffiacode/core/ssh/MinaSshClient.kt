package com.konprostart.tariffiacode.core.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.loader.KeyPairResourceParser
import org.apache.sshd.common.util.security.SecurityUtils
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.SocketAddress
import java.security.KeyPair
import java.security.PublicKey
import java.util.Base64
import org.apache.sshd.client.SshClient as MinaApacheSshClient

/**
 * Apache MINA SSHD backed [SshConnectionClient]. Pure-JVM and Android-compatible, isolated behind the
 * [SshConnectionClient] interface so nothing else depends on MINA types.
 *
 * Host keys are NEVER auto-accepted: the verifier is consulted during the handshake and, unless it
 * returns [SshHostKeyDecision.Trusted], the connection is refused so the UI can present the fingerprint.
 *
 * Channel I/O is bound through MINA's supported [ChannelShell.setIn]/[ChannelShell.setOut]/
 * [ChannelShell.setErr] stream API rather than relying on the `getInvertedOut`/`getInvertedErr`
 * accessors: we own the pipes, which keeps Kotlin/Java interop explicit and stable.
 */
class MinaSshClient(
    private val clientFactory: () -> MinaApacheSshClient = { MinaApacheSshClient.setUpDefaultClient() },
) : SshConnectionClient {
    override suspend fun connect(
        host: String,
        port: Int,
        auth: SshAuth,
        verifier: SshHostKeyVerifier,
        connectTimeoutMillis: Long,
    ): SshConnectResult =
        withContext(Dispatchers.IO) {
            val client = clientFactory()
            val hostKeyVerifier = FingerprintServerKeyVerifier(host, port, verifier)
            client.serverKeyVerifier = hostKeyVerifier
            var session: ClientSession? = null
            try {
                client.start()
                // MINA's verify(timeout) blocks until the handshake (including our host-key check)
                // completes or throws, so no coroutine-future adapter is needed.
                session =
                    client.connect(auth.username, host, port)
                        .verify(connectTimeoutMillis)
                        .session

                when (auth) {
                    is SshAuth.Password -> session.addPasswordIdentity(auth.password)
                    is SshAuth.PrivateKey -> session.addPublicKeyIdentity(loadKeyPair(auth))
                }
                session.auth().verify(AUTH_TIMEOUT_MILLIS)

                val channel = session.createShellChannel()
                channel.setUsePty(true)
                channel.ptyType = "xterm-256color"
                channel.setEnv("TERM", "xterm-256color")

                // Bind our own stdin/stdout/stderr pipes BEFORE opening the channel so no remote
                // output is missed once the shell starts.
                val sshSession = MinaSshSession(session, channel)
                channel.setIn(sshSession.remoteInput)
                channel.setOut(sshSession.remoteOutput)
                channel.setErr(sshSession.remoteError)
                channel.open().verify(connectTimeoutMillis)

                SshConnectResult.Connected(sshSession)
            } catch (e: Exception) {
                runCatching { session?.close() }
                // MINA wraps verifier-rejection exceptions, so we surface an untrusted host key from
                // the verifier's recorded state rather than relying on exception types.
                val untrusted = hostKeyVerifier.untrustedKey
                if (untrusted != null) {
                    SshConnectResult.HostKeyUntrusted(untrusted)
                } else {
                    SshConnectResult.Failure(e.message ?: "SSH connection failed", e)
                }
            } finally {
                runCatching { client.stop() }
            }
        }

    private fun loadKeyPair(auth: SshAuth.PrivateKey): KeyPair {
        val parser: KeyPairResourceParser = SecurityUtils.getKeyPairResourceParser()
        val provider =
            auth.passphrase
                ?.let { org.apache.sshd.common.config.keys.FilePasswordProvider.of(it) }
                ?: org.apache.sshd.common.config.keys.FilePasswordProvider.EMPTY
        val pairs = parser.loadKeyPairs(null, null, provider, auth.keyPem.lineSequence().toList())
        return pairs.firstOrNull() ?: throw IOException("No private key found in the provided key material")
    }

    private companion object {
        const val AUTH_TIMEOUT_MILLIS = 30_000L
    }
}

/**
 * MINA server-key verifier that defers to the app's [SshHostKeyVerifier]. Unknown keys are rejected
 * (return false) and recorded in [untrustedKey] so the UI can show the fingerprint; a mismatch against
 * a trusted key is rejected too. Exceptions are avoided because MINA wraps them during the handshake.
 */
internal class FingerprintServerKeyVerifier(
    private val host: String,
    private val port: Int,
    private val delegate: SshHostKeyVerifier,
) : ServerKeyVerifier {
    /** Set when the presented key is not yet trusted; the connection is rejected in that case. */
    @Volatile
    var untrustedKey: SshHostKey? = null
        private set

    override fun verifyServerKey(
        clientSession: ClientSession,
        remoteAddress: SocketAddress,
        serverKey: PublicKey,
    ): Boolean {
        // MINA renders the key as "ssh-ed25519 <base64-wire-blob>"; SHA-256 of that raw blob is the
        // same fingerprint OpenSSH shows.
        val blob = Base64.getDecoder().decode(PublicKeyEntry.toString(serverKey).substringAfter(' ').trim())
        val hostKey = SshHostKey(keyType = KeyUtils.getKeyType(serverKey), sha256Fingerprint = SshFingerprint.sha256(blob))
        return when (delegate.verify(host, port, hostKey)) {
            is SshHostKeyDecision.Trusted -> true
            is SshHostKeyDecision.Unknown -> {
                untrustedKey = hostKey
                false
            }
        }
    }
}

/**
 * A live interactive shell backed by a MINA [ChannelShell].
 *
 * MINA writes remote stdout/stderr into the [PipedOutputStream]s we hand to `setOut`/`setErr`; the
 * paired [PipedInputStream]s are drained here. stdin is the opposite direction: bytes written to
 * [writeStandardInput] go into a pipe whose read side MINA consumes via `setIn`.
 */
private class MinaSshSession(
    private val session: ClientSession,
    private val channel: ChannelShell,
) : SshSession {
    private val stdoutPipe = PipedOutputStream()
    private val stderrPipe = PipedOutputStream()
    private val stdinPipe = PipedOutputStream()

    /** Read side MINA consumes for remote stdin. */
    val remoteInput: PipedInputStream = PipedInputStream(stdinPipe, PIPE_BUFFER_SIZE)

    /** Write side MINA fills with remote stdout. */
    val remoteOutput: PipedOutputStream = stdoutPipe

    /** Write side MINA fills with remote stderr. */
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
                    if (!readAny) {
                        delay(POLL_INTERVAL_MILLIS)
                    }
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

    override fun close() {
        if (!open) return
        open = false
        runCatching { stdinPipe.close() }
        runCatching { stdoutPipe.close() }
        runCatching { stderrPipe.close() }
        runCatching { channel.close(false) }
        runCatching { session.close(false) }
    }

    private companion object {
        const val PIPE_BUFFER_SIZE = 8192
        const val POLL_INTERVAL_MILLIS = 20L
    }
}
