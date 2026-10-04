package com.konprostart.tariffiacode.core.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import org.apache.sshd.client.SshClient as MinaApacheSshClient
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.channel.ClientChannel
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.loader.KeyPairResourceParser
import org.apache.sshd.common.util.security.SecurityUtils
import java.io.IOException
import java.net.SocketAddress
import java.security.KeyPair
import java.security.PublicKey
import java.util.Base64
import java.util.EnumSet

/**
 * Apache MINA SSHD backed [SshClient]. Pure-JVM and Android-compatible, isolated behind the [SshClient]
 * interface so nothing else depends on MINA types.
 *
 * Host keys are NEVER auto-accepted: the verifier is consulted during the handshake and, unless it
 * returns [SshHostKeyDecision.Trusted], the connection is refused so the UI can present the fingerprint.
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
            client.serverKeyVerifier = FingerprintServerKeyVerifier(host, port, verifier)
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
                channel.open().verify(connectTimeoutMillis)

                SshConnectResult.Connected(MinaSshSession(session, channel))
            } catch (e: HostKeyUntrustedException) {
                runCatching { session?.close() }
                SshConnectResult.HostKeyUntrusted(e.hostKey)
            } catch (e: Exception) {
                runCatching { session?.close() }
                SshConnectResult.Failure(e.message ?: "SSH connection failed", e)
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

/** Thrown internally when the user has not yet trusted a presented host key. */
internal class HostKeyUntrustedException(
    val hostKey: SshHostKey,
) : IOException("SSH host key is not trusted")

/**
 * MINA server-key verifier that defers to the app's [SshHostKeyVerifier]. Unknown keys abort the
 * handshake so the UI can show the fingerprint; a mismatch against a trusted key is rejected too.
 */
internal class FingerprintServerKeyVerifier(
    private val host: String,
    private val port: Int,
    private val delegate: SshHostKeyVerifier,
) : ServerKeyVerifier {
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
            is SshHostKeyDecision.Unknown -> throw HostKeyUntrustedException(hostKey)
        }
    }
}

/** A live interactive shell backed by a MINA [ChannelShell]. */
private class MinaSshSession(
    private val session: ClientSession,
    private val channel: ClientChannel,
) : SshSession {
    override val output: Flow<ByteArray> =
        channelFlow {
            val out = channel.invertedOut
            val err = channel.invertedErr
            val buffer = ByteArray(4096)
            try {
                while (channel.isOpen) {
                    var readAny = false
                    if (out.available() > 0) {
                        val n = out.read(buffer)
                        if (n > 0) {
                            trySend(buffer.copyOf(n))
                            readAny = true
                        }
                    }
                    if (err.available() > 0) {
                        val n = err.read(buffer)
                        if (n > 0) {
                            trySend(buffer.copyOf(n))
                            readAny = true
                        }
                    }
                    if (!readAny) {
                        channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 50L)
                    }
                }
            } finally {
                close()
            }
            awaitClose { close() }
        }

    override fun writeStandardInput(bytes: ByteArray) {
        channel.invertedIn.write(bytes)
        channel.invertedIn.flush()
    }

    override fun resize(
        columns: Int,
        rows: Int,
    ) {
        runCatching { channel.sendWindowChange(columns, rows) }
    }

    override val isOpen: Boolean
        get() = channel.isOpen && session.isOpen

    override fun close() {
        runCatching { channel.close(false) }
        runCatching { session.close(false) }
    }
}
