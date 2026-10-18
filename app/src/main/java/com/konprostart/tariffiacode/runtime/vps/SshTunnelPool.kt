package com.konprostart.tariffiacode.runtime.vps

import com.konprostart.tariffiacode.core.ssh.SshAuth
import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.core.ssh.SshHostKeyVerifier
import com.konprostart.tariffiacode.core.ssh.SshPortForward
import com.konprostart.tariffiacode.core.ssh.SshPortForwardResult
import com.konprostart.tariffiacode.core.ssh.SshPortForwarder
import com.konprostart.tariffiacode.core.ssh.SshSessionSharingForwarder
import com.konprostart.tariffiacode.core.ssh.SshTunnelSession
import com.konprostart.tariffiacode.core.ssh.SshTunnelSessionResult
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Identifies one tunnel: where the SSH connection goes, and where on the server it points.
 *
 * [host]/[port] are the profile's SSH endpoint, [remoteHost]/[remotePort] the service behind the
 * tunnel.
 */
data class TunnelKey(
    val profileId: String,
    val host: String,
    val port: Int,
    val remoteHost: String,
    val remotePort: Int,
    /**
     * Local port the forward binds, or [SshPortForwarder.EPHEMERAL_LOCAL_PORT] for any free one.
     * Part of the key because two forwards to the same remote port that bind different local
     * ports are two different channels, and sharing one of them would serve the wrong address.
     */
    val localPort: Int = SshPortForwarder.EPHEMERAL_LOCAL_PORT,
)

/**
 * Shares one SSH session per endpoint across every tunnel that needs it.
 *
 * The app talks to the same VPS from several places at once: the OpenCode chat tunnel (the server's
 * 127.0.0.1:4096) and the live preview of whatever dev server the user started (a different port), and
 * every remote-git write opened yet another short-lived session. Each paid a full TCP connect, key
 * exchange and authentication round-trip, so the device held several equally expensive connections to
 * one host and repeated the handshake every time a tunnel was opened.
 *
 * Pooling is two-level because the two resources cost very different amounts:
 *
 *  - a **session** per endpoint ([TunnelKey.profileId] plus its host and port) is the expensive half - one handshake, one
 *    authentication, one keep-alive - and is shared by every tunnel to that server;
 *  - a **forward** per [TunnelKey] is one channel on it, cheap to add, and is what a caller holds.
 *
 * Both are reference-counted, so a session survives until its last tunnel lets go. Callers treat what
 * they are given as their own [AutoCloseable] and need not know it is shared: closing it releases their
 * share, and at zero the tunnel goes, and at zero tunnels the session goes with it.
 *
 * The endpoint is part of the session key, not just the profile id: editing a profile's host must not
 * let a tunnel reach the old host on a session that was trusted for it.
 *
 * Safe to call from any thread. A tunnel or connection that died on its own is rebuilt on the next
 * acquire instead of being handed out again, and a failed connect is never cached - so an untrusted
 * host key keeps being reported until the user trusts it.
 */
class SshTunnelPool(
    private val forwarder: SshSessionSharingForwarder,
) {
    /** One connection, shared by every tunnel to the same endpoint. */
    private class SessionEntry(
        val id: SessionId,
        val session: SshTunnelSession,
    ) {
        var holders: Int = 0
        val forwards = mutableMapOf<TunnelKey, ForwardEntry>()
    }

    private class ForwardEntry(
        val forward: SshPortForward,
    ) {
        var holders: Int = 0
    }

    private data class SessionId(
        val profileId: String,
        val host: String,
        val port: Int,
    )

    private val lock = Any()
    private val sessions = mutableMapOf<SessionId, SessionEntry>()

    /**
     * Open (or join) the tunnel described by [key].
     *
     * [auth] and [verifier] are only consulted when a connection actually has to be established; binding
     * on a connection that is already up costs nothing, which is the whole point.
     */
    suspend fun open(
        key: TunnelKey,
        auth: SshAuth,
        verifier: SshHostKeyVerifier,
        connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    ): SshPortForwardResult {
        val id = key.sessionId()
        join(id, key)?.let { return SshPortForwardResult.Listening(it) }

        // A live connection to this endpoint is worth far more than a fresh handshake: bind on it.
        val existing = synchronized(lock) { sessions[id]?.takeIf { it.session.isOpen } }
        if (existing != null) {
            return when (val bound = bind(existing.session, key)) {
                is SshPortForwardResult.Listening ->
                    SshPortForwardResult.Listening(acquire(existing, key, bound.forward))
                else -> bound
            }
        }

        return when (val established = establish(key, auth, verifier, connectTimeoutMillis)) {
            is Establishment.Failure -> established.result
            is Establishment.HostKeyUntrusted -> SshPortForwardResult.HostKeyUntrusted(established.hostKey)
            is Establishment.Established -> adopt(id, key, established)
        }
    }

    /** A share of an already-open tunnel, or null when one has to be opened. */
    private fun join(
        id: SessionId,
        key: TunnelKey,
    ): SshPortForward? =
        synchronized(lock) {
            val entry = sessions[id]
            if (entry == null) {
                null
            } else if (!entry.session.isOpen) {
                // A connection that died is rebuilt on the next acquire, never handed out again.
                sessions.remove(id)
                runCatching { entry.session.close() }
                null
            } else {
                val forwardEntry = entry.forwards[key]
                when {
                    forwardEntry == null -> null
                    !forwardEntry.forward.isOpen -> {
                        entry.forwards.remove(key)
                        runCatching { forwardEntry.forward.close() }
                        null
                    }
                    // `retainLocked`, not `acquireLocked`: the forward here is the one already being
                    // shared, and `acquireLocked` closes whatever it is given as redundant.
                    else -> retainLocked(entry, key, forwardEntry)
                }
            }
        }

    /**
     * Adds a freshly bound forward to [entry], or to the connection that won a race.
     *
     * The losing session is closed and its tunnel rebound on the winner's, rather than leaving two
     * live connections to one host.
     */
    private suspend fun adopt(
        id: SessionId,
        key: TunnelKey,
        established: Establishment.Established,
    ): SshPortForwardResult {
        val winner =
            synchronized(lock) {
                val existing = sessions[id]
                if (existing != null && existing.session.isOpen) {
                    existing
                } else {
                    if (existing != null) runCatching { existing.session.close() }
                    SessionEntry(id, established.session).also { sessions[id] = it }
                }
            }
        if (winner.session === established.session) {
            return SshPortForwardResult.Listening(acquire(winner, key, established.forward))
        }
        runCatching { established.session.close() }
        runCatching { established.forward.close() }
        return when (val bound = bind(winner.session, key)) {
            is SshPortForwardResult.Listening -> SshPortForwardResult.Listening(acquire(winner, key, bound.forward))
            else -> bound
        }
    }

    /** Registers a forward against [entry] and returns the caller's handle. Must hold the lock. */
    private fun acquireLocked(
        entry: SessionEntry,
        key: TunnelKey,
        forward: SshPortForward,
    ): SshPortForward {
        // Read first rather than getOrPut: if someone registered this tunnel while we were binding,
        // the forward we just bound is redundant, and its loopback listener would otherwise stay open
        // for the life of the process with nothing holding it.
        val registered = entry.forwards[key]
        if (registered != null && registered.forward.isOpen) {
            runCatching { forward.close() }
            return retainLocked(entry, key, registered)
        }
        // A registered-but-dead channel is replaced by the one just bound.
        if (registered != null) {
            entry.forwards.remove(key)
            runCatching { registered.forward.close() }
        }
        val forwardEntry = ForwardEntry(forward)
        entry.forwards[key] = forwardEntry
        return retainLocked(entry, key, forwardEntry)
    }

    /**
     * Adds a holder to a tunnel that is already registered and open. Must hold the lock.
     *
     * The single place holder arithmetic happens; [acquireLocked] and [join] both land here.
     */
    private fun retainLocked(
        entry: SessionEntry,
        key: TunnelKey,
        forwardEntry: ForwardEntry,
    ): SshPortForward {
        entry.holders++
        forwardEntry.holders++
        return lease(entry, key, forwardEntry.forward)
    }

    private fun acquire(
        entry: SessionEntry,
        key: TunnelKey,
        forward: SshPortForward,
    ): SshPortForward = synchronized(lock) { acquireLocked(entry, key, forward) }

    private fun lease(
        entry: SessionEntry,
        key: TunnelKey,
        forward: SshPortForward,
    ): SshPortForward =
        object : SshPortForward by forward {
            private val released = AtomicBoolean(false)

            override fun close() {
                if (!released.compareAndSet(false, true)) return
                release(entry, key, forward)
            }
        }

    /**
     * Drops one holder: the tunnel at zero, and the connection when its last tunnel goes.
     *
     * The entry is passed by reference so a lease that outlives its connection - only possible after
     * [closeAll] - cannot decrement a connection someone else opened in the meantime.
     */
    private fun release(
        entry: SessionEntry,
        key: TunnelKey,
        forward: SshPortForward,
    ) {
        synchronized(lock) {
            if (sessions[entry.id] !== entry) return
            entry.holders = (entry.holders - 1).coerceAtLeast(0)
            val forwardEntry = entry.forwards[key]
            if (forwardEntry != null && forwardEntry.forward === forward) {
                forwardEntry.holders = (forwardEntry.holders - 1).coerceAtLeast(0)
                if (forwardEntry.holders <= 0) {
                    entry.forwards.remove(key)
                    runCatching { forward.close() }
                }
            }
            if (entry.holders <= 0) {
                sessions.remove(entry.id)
                runCatching { entry.session.close() }
            }
        }
    }

    /** How many callers currently hold a tunnel to this profile. Exposed for tests and diagnostics. */
    fun holders(profileId: String): Int =
        synchronized(lock) {
            sessions.entries.filter { it.key.profileId == profileId }.sumOf { it.value.holders }
        }

    /** How many distinct tunnels are open for this profile. Exposed for tests and diagnostics. */
    fun openTunnels(profileId: String): Int =
        synchronized(lock) {
            sessions.entries.filter { it.key.profileId == profileId }.sumOf { it.value.forwards.size }
        }

    /**
     * Drops every connection and tunnel, whatever still holds them. Leases handed out earlier become
     * no-ops on close rather than affecting whatever opens next.
     */
    fun closeAll() {
        synchronized(lock) {
            val live = sessions.values.toList()
            sessions.clear()
            live.forEach { entry ->
                entry.forwards.values.forEach { runCatching { it.forward.close() } }
                runCatching { entry.session.close() }
            }
        }
    }

    private fun TunnelKey.sessionId() = SessionId(profileId = profileId, host = host, port = port)

    private suspend fun bind(
        session: SshTunnelSession,
        key: TunnelKey,
    ): SshPortForwardResult = forwarder.openForwardOver(session, key.remoteHost, key.remotePort, key.localPort)

    private sealed interface Establishment {
        class Established(
            val session: SshTunnelSession,
            val forward: SshPortForward,
        ) : Establishment

        class HostKeyUntrusted(
            val hostKey: SshHostKey,
        ) : Establishment

        class Failure(
            val result: SshPortForwardResult.Failure,
        ) : Establishment
    }

    /** Opens a connection and one forward on it, outside the lock because it does real IO. */
    private suspend fun establish(
        key: TunnelKey,
        auth: SshAuth,
        verifier: SshHostKeyVerifier,
        connectTimeoutMillis: Long,
    ): Establishment {
        var session: SshTunnelSession? = null
        // `return try` rather than a bare try: a try/catch is not implicitly returned as the tail
        // expression of a block body.
        return try {
            val opened =
                forwarder.openSession(
                    host = key.host,
                    port = key.port,
                    auth = auth,
                    verifier = verifier,
                    connectTimeoutMillis = connectTimeoutMillis,
                )
            // A non-null local rather than reading `session` back: the two early returns above make
            // `session` keep its nullable type, and a mutable local captured in the failure handlers
            // below cannot be smart cast. Assigning it here also means both `catch` blocks can see a
            // connection to close if anything from here on throws.
            val openedSession =
                when (opened) {
                    is SshTunnelSessionResult.HostKeyUntrusted -> return Establishment.HostKeyUntrusted(opened.hostKey)
                    is SshTunnelSessionResult.Failure ->
                        return Establishment.Failure(SshPortForwardResult.Failure(opened.message, opened.cause))
                    is SshTunnelSessionResult.Opened -> opened.session
                }
            session = openedSession
            return when (val bound = bind(openedSession, key)) {
                is SshPortForwardResult.Listening -> Establishment.Established(openedSession, bound.forward)
                // Both failure paths leave the `try`, so neither `catch` below runs: the connection
                // that was just opened and authenticated has to be released here or it - the MINA
                // client, its threads and the live session - would leak for the life of the process.
                is SshPortForwardResult.Failure ->
                    Establishment.Failure(bound).also { runCatching { openedSession.close() } }
                // The handshake already happened, so a key prompt cannot appear here; report it rather
                // than inventing a failure type the caller would have to special-case.
                is SshPortForwardResult.HostKeyUntrusted ->
                    Establishment
                        .Failure(SshPortForwardResult.Failure("Unexpected host-key prompt while binding a forward"))
                        .also { runCatching { openedSession.close() } }
            }
        } catch (e: CancellationException) {
            runCatching { session?.close() }
            throw e
        } catch (e: Throwable) {
            runCatching { session?.close() }
            Establishment.Failure(SshPortForwardResult.Failure("Tunnel setup failed", e))
        }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000L
    }
}
