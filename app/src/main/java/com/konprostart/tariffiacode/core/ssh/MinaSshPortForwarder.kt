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
 *
 * Also an [SshSessionSharingForwarder]: connecting and binding a port are separate steps here, so one
 * session can carry many forwards. [openLocalForward] keeps doing both, and owns what it opens.
 */
class MinaSshPortForwarder(
    private val clientFactory: () -> MinaApacheSshClient = { MinaApacheSshClient.setUpDefaultClient() },
) : SshSessionSharingForwarder {
    override suspend fun openSession(
        host: String,
        port: Int,
        auth: SshAuth,
        verifier: SshHostKeyVerifier,
        connectTimeoutMillis: Long,
    ): SshTunnelSessionResult =
        withContext(Dispatchers.IO) {
            var client: MinaApacheSshClient? = null
            var hostKeyVerifier: FingerprintServerKeyVerifier? = null
            var session: ClientSession? = null
            var phase = "clientFactory()"
            try {
                val created = clientFactory()
                client = created
                val createdVerifier = FingerprintServerKeyVerifier(host, port, verifier)
                hostKeyVerifier = createdVerifier
                created.serverKeyVerifier = createdVerifier

                phase = "start()"
                SshKeepAlive.enableOn(created)
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
                connectedSession.applySshAuth(auth)
                connectedSession.auth().verify(AUTH_TIMEOUT_MILLIS)

                SshTunnelSessionResult.Opened(MinaSshTunnelSession(created, connectedSession))
            } catch (e: CancellationException) {
                runCatching { session?.close(false) }
                runCatching { client?.stop() }
                throw e
            } catch (e: Exception) {
                describeSessionFailure(phase, e, hostKeyVerifier, client, session)
            } catch (e: LinkageError) {
                // Android class-linkage failures are Errors, not Exceptions. Fatal VM errors such as
                // OutOfMemoryError remain uncaught.
                describeSessionFailure(phase, e, hostKeyVerifier, client, session)
            }
        }

    override suspend fun openForwardOver(
        session: SshTunnelSession,
        remoteHost: String,
        remotePort: Int,
        localPort: Int,
    ): SshPortForwardResult {
        val mina = session as? MinaSshTunnelSession ?: return SshPortForwardResult.Failure("Session is not a MINA session")
        return withContext(Dispatchers.IO) { bindForward(mina, remoteHost, remotePort, ownedSession = null, localPort = localPort) }
    }

    override suspend fun openLocalForward(
        host: String,
        port: Int,
        auth: SshAuth,
        verifier: SshHostKeyVerifier,
        remoteHost: String,
        remotePort: Int,
        connectTimeoutMillis: Long,
        localPort: Int,
    ): SshPortForwardResult {
        val opened =
            when (val result = openSession(host, port, auth, verifier, connectTimeoutMillis)) {
                is SshTunnelSessionResult.Opened -> result.session
                is SshTunnelSessionResult.HostKeyUntrusted ->
                    return SshPortForwardResult.HostKeyUntrusted(result.hostKey)
                is SshTunnelSessionResult.Failure ->
                    return SshPortForwardResult.Failure(result.message, result.cause)
            }
        // Only sessions this class opens reach here, so the cast cannot fail in practice; it is checked
        // rather than assumed so a foreign implementation fails loudly instead of crashing later.
        val session =
            opened as? MinaSshTunnelSession
                ?: return SshPortForwardResult.Failure("Session is not a MINA session")
        val handedOffForward = AtomicReference<SshPortForward?>(null)
        // `return try` rather than a bare try: a try/catch is not implicitly returned as the tail
        // expression of a block body.
        return try {
            val bound = bindForward(session, remoteHost, remotePort, ownedSession = session, localPort = localPort)
            if (bound is SshPortForwardResult.Listening) {
                handedOffForward.set(bound.forward)
                bound
            } else {
                // Nothing was bound, so the session this method opened has no owner left for it.
                runCatching { session.close() }
                bound
            }
        } catch (e: Throwable) {
            // withContext can notice cancellation after binding returns a forward but before handing
            // the result back to the caller. Close that orphaned ownership handle.
            runCatching { handedOffForward.getAndSet(null)?.close() }
            runCatching { session.close() }
            throw e
        }
    }

    /** Binds a loopback port to [remoteHost]:[remotePort] on an already-open session. */
    private suspend fun bindForward(
        session: MinaSshTunnelSession,
        remoteHost: String,
        remotePort: Int,
        ownedSession: MinaSshTunnelSession?,
        localPort: Int,
    ): SshPortForwardResult =
        try {
            // Bind [localPort] on the device's loopback - ephemeral (0) unless a caller needs a known
            // address; the OS-assigned port is read back from the tracker's bound address either way.
            val local = SshdSocketAddress(LOCAL_HOST, localPort)
            val remote = SshdSocketAddress(remoteHost, remotePort)
            val tracker = session.connectedSession.createLocalPortForwardingTracker(local, remote)
            SshPortForwardResult.Listening(
                MinaSshPortForward(
                    localHost = LOCAL_HOST,
                    localPort = tracker.boundAddress.port,
                    remoteHost = remoteHost,
                    remotePort = remotePort,
                    session = session.connectedSession,
                    tracker = tracker,
                    ownedSession = ownedSession,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SshPortForwardResult.Failure(describe(phase = "portForward()", error = e), e)
        } catch (e: LinkageError) {
            SshPortForwardResult.Failure(describe(phase = "portForward()", error = e), e)
        }

    /** Builds the failure text shared by the session and forward steps. */
    private fun describe(
        phase: String,
        error: Throwable,
    ): String {
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
        return "$phase failed: $causeTypes$missingClassDetail"
    }

    private fun describeSessionFailure(
        phase: String,
        error: Throwable,
        hostKeyVerifier: FingerprintServerKeyVerifier?,
        client: MinaApacheSshClient?,
        session: ClientSession?,
    ): SshTunnelSessionResult {
        runCatching { session?.close(false) }
        runCatching { client?.stop() }
        val untrusted = hostKeyVerifier?.untrustedKey
        return if (untrusted != null) {
            SshTunnelSessionResult.HostKeyUntrusted(untrusted)
        } else {
            SshTunnelSessionResult.Failure(describe(phase, error), error)
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
 * A MINA-backed forward over an existing session. Releases the tracker and its session on [close], plus
 * [ownedSession] when this forward is the one that opened it - so a pooled forward leaves the shared
 * session running while an owned one takes everything it opened down with it.
 */
private class MinaSshPortForward(
    override val localHost: String,
    override val localPort: Int,
    override val remoteHost: String,
    override val remotePort: Int,
    private val session: ClientSession,
    private val tracker: ExplicitPortForwardingTracker,
    private val ownedSession: MinaSshTunnelSession?,
) : SshPortForward {
    @Volatile
    private var closed = false

    /**
     * Open only while the local listener is up *and* the SSH session behind it is still open.
     *
     * [ExplicitPortForwardingTracker.isOpen] is our own bookkeeping: a forward whose binding was
     * dropped without a close keeps reporting true, so this is still a statement about the listener,
     * not a probe of the far end. The session's own state is what MINA clears when the transport
     * goes away; a dead peer that never sends anything (the silent NAT case) is only detected by
     * actually talking to the server - see the runtime's health check and its network-handover
     * recovery, which exist precisely because this cannot tell.
     */
    override val isOpen: Boolean
        get() = !closed && tracker.isOpen && session.isOpen

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        // Order matters: stop forwarding first (closes the listener and any in-flight channels), then the
        // session, then whatever opened that session.
        runCatching { tracker.close() }
        runCatching { session.close(false) }
        runCatching { ownedSession?.close() }
    }
}

/** A MINA client + session held together so a pool can share one across many forwards. */
internal class MinaSshTunnelSession(
    private val client: MinaApacheSshClient,
    /** The live MINA session, used to open further forwards on this connection. */
    val connectedSession: ClientSession,
) : SshTunnelSession {
    @Volatile
    private var closed = false

    override val isOpen: Boolean
        get() = !closed && connectedSession.isOpen

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { connectedSession.close(false) }
        runCatching { client.stop() }
    }
}
