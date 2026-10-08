package com.konprostart.tariffiacode.core.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.client.session.forward.ExplicitPortForwardingTracker
import org.apache.sshd.common.util.net.SshdSocketAddress
import java.util.concurrent.atomic.AtomicReference
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
    ): SshPortForwardResult {
        val handedOffForward = AtomicReference<SshPortForward?>(null)
        try {
            return withContext(Dispatchers.IO) {
                var client: MinaApacheSshClient? = null
                var hostKeyVerifier: FingerprintServerKeyVerifier? = null
                var session: ClientSession? = null
                var tracker: ExplicitPortForwardingTracker? = null
                var phase = "clientFactory()"

                fun cleanupPartialForward() {
                    runCatching { tracker?.close() }
                    runCatching { session?.close(false) }
                    runCatching { client?.stop() }
                }

                fun failure(error: Throwable): SshPortForwardResult {
                    cleanupPartialForward()
                    val untrusted = hostKeyVerifier?.untrustedKey
                    if (untrusted != null) return SshPortForwardResult.HostKeyUntrusted(untrusted)

                    val causeTypes =
                        generateSequence(error) { it.cause }
                            .take(8)
                            .joinToString(" <- ") { it::class.java.simpleName }
                    val missingClass =
                        generateSequence(error) { it.cause }
                            .filter { it is LinkageError || it is ClassNotFoundException }
                            .mapNotNull { cause -> cause.message?.takeIf { SAFE_CLASS_NAME.matches(it) } }
                            .firstOrNull()
                    val missingClassDetail = missingClass?.let { " ($it)" }.orEmpty()
                    return SshPortForwardResult.Failure("$phase failed: $causeTypes$missingClassDetail", error)
                }

                try {
                    val created = clientFactory()
                    client = created
                    val createdVerifier = FingerprintServerKeyVerifier(host, port, verifier)
                    hostKeyVerifier = createdVerifier
                    created.serverKeyVerifier = createdVerifier

                    phase = "start()"
                    created.start()
                    // MINA's verify(timeout) blocks until the handshake (including our host-key check)
                    // completes or throws, so no coroutine-future adapter is needed.
                    phase = "connect()"
                    val connectedSession =
                        created.connect(auth.username, host, port)
                            .verify(connectTimeoutMillis)
                            .session
                    session = connectedSession

                    phase = "auth()"
                    when (auth) {
                        is SshAuth.Password -> connectedSession.addPasswordIdentity(auth.password)
                        is SshAuth.PrivateKey -> connectedSession.addPublicKeyIdentity(SshKeyPairs.load(auth))
                    }
                    connectedSession.auth().verify(AUTH_TIMEOUT_MILLIS)

                    // Bind an ephemeral port on the device's loopback; the OS assigns the real port,
                    // which we read back from the tracker's bound address.
                    val local = SshdSocketAddress(LOCAL_HOST, 0)
                    val remote = SshdSocketAddress(remoteHost, remotePort)
                    phase = "portForward()"
                    val createdTracker = connectedSession.createLocalPortForwardingTracker(local, remote)
                    tracker = createdTracker
                    val forward =
                        MinaSshPortForward(
                            localHost = LOCAL_HOST,
                            localPort = createdTracker.boundAddress.port,
                            remoteHost = remoteHost,
                            remotePort = remotePort,
                            client = created,
                            session = connectedSession,
                            tracker = createdTracker,
                        )
                    handedOffForward.set(forward)
                    SshPortForwardResult.Listening(forward)
                } catch (e: CancellationException) {
                    cleanupPartialForward()
                    throw e
                } catch (e: Exception) {
                    failure(e)
                } catch (e: LinkageError) {
                    // Android class-linkage failures are Errors, not Exceptions. Fatal VM errors
                    // such as OutOfMemoryError remain uncaught.
                    failure(e)
                }
            }
        } catch (e: Throwable) {
            // withContext can notice cancellation after blocking MINA setup returns a forward but
            // before handing the result back to the caller. Close that orphaned ownership handle.
            runCatching { handedOffForward.getAndSet(null)?.close() }
            throw e
        }
    }

    private companion object {
        const val AUTH_TIMEOUT_MILLIS = 30_000L

        /** Loopback only: the tunnel must not expose the forwarded service to the device's networks. */
        const val LOCAL_HOST = "127.0.0.1"

        // Only display class-like linkage targets; never copy arbitrary exception text into the UI.
        private val SAFE_CLASS_NAME = Regex("[A-Za-z0-9_.$/;]+")
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
