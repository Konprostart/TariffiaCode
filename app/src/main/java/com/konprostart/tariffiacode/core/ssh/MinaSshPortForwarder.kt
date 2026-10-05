package com.konprostart.tariffiacode.core.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.client.session.forward.ExplicitPortForwardingTracker
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.client.SshClient as MinaApacheSshClient

/**
 * Apache MINA SSHD backed [SshPortForwarder]. Pure-JVM and Android-compatible, isolated behind the
 * [SshPortForwarder] interface so nothing else depends on MINA types.
 *
 * Host keys are NEVER auto-accepted: the verifier is consulted during the handshake and, unless it
 * returns [SshHostKeyDecision.Trusted], the connection is refused so the UI can present the fingerprint -
 * the same policy the shell transport uses.
 *
 * The forward listens on the device's loopback only ([LOCAL_HOST]) and targets [SshPortForward.remoteHost]:
 * [SshPortForward.remotePort] as seen from the SSH server, so a service bound to the server's own loopback
 * (e.g. `opencode serve --hostname 127.0.0.1 --port 4096`) becomes reachable through the tunnel.
 */
class MinaSshPortForwarder(
    private val clientFactory: () -> MinaApacheSshClient = { MinaApacheSshClient.setUpDefaultClient() },
) : SshPortForwarder {
    override suspend fun openLocalForward(
        host: String,
        port: Int,
        auth: SshAuth,
        verifier: SshHostKeyVerifier,
        remoteHost: String,
        remotePort: Int,
        connectTimeoutMillis: Long,
    ): SshPortForwardResult =
        withContext(Dispatchers.IO) {
            val client = clientFactory()
            val hostKeyVerifier = FingerprintServerKeyVerifier(host, port, verifier)
            client.serverKeyVerifier = hostKeyVerifier
            var session: ClientSession? = null
            var tracker: ExplicitPortForwardingTracker? = null
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
                    is SshAuth.PrivateKey -> session.addPublicKeyIdentity(SshKeyPairs.load(auth))
                }
                session.auth().verify(AUTH_TIMEOUT_MILLIS)

                // Bind an ephemeral port on the device's loopback; the OS assigns the real port, which
                // we read back from the tracker's bound address.
                val local = SshdSocketAddress(LOCAL_HOST, 0)
                val remote = SshdSocketAddress(remoteHost, remotePort)
                tracker = session.createLocalPortForwardingTracker(local, remote)

                SshPortForwardResult.Listening(
                    MinaSshPortForward(
                        localHost = LOCAL_HOST,
                        localPort = tracker.boundAddress.port,
                        remoteHost = remoteHost,
                        remotePort = remotePort,
                        client = client,
                        session = session,
                        tracker = tracker,
                    ),
                )
            } catch (e: Exception) {
                // Never let a partially established forward leak its client/session.
                runCatching { tracker?.close() }
                runCatching { session?.close(false) }
                runCatching { client.stop() }
                // MINA wraps verifier-rejection exceptions, so surface an untrusted host key from the
                // verifier's recorded state rather than relying on exception types.
                val untrusted = hostKeyVerifier.untrustedKey
                if (untrusted != null) {
                    SshPortForwardResult.HostKeyUntrusted(untrusted)
                } else {
                    SshPortForwardResult.Failure(e.message ?: "SSH port forward failed", e)
                }
            }
        }

    private companion object {
        const val AUTH_TIMEOUT_MILLIS = 30_000L

        /** Loopback only: the tunnel must not expose the forwarded service to the device's networks. */
        const val LOCAL_HOST = "127.0.0.1"
    }
}

/**
 * A MINA-backed forward. Holds the SSH client, session and forwarding tracker and releases all three on
 * [close], so the forward's lifetime is tied to the session that carries it.
 */
private class MinaSshPortForward(
    override val localHost: String,
    override val localPort: Int,
    override val remoteHost: String,
    override val remotePort: Int,
    private val client: MinaApacheSshClient,
    private val session: ClientSession,
    private val tracker: ExplicitPortForwardingTracker,
) : SshPortForward {
    @Volatile
    private var closed = false

    override val isOpen: Boolean
        get() = !closed && tracker.isOpen && session.isOpen

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        // Order matters: stop forwarding first (closes the listener and any in-flight channels), then the
        // session, then the client that owns it.
        runCatching { tracker.close() }
        runCatching { session.close(false) }
        runCatching { client.stop() }
    }
}
