package com.konprostart.tariffiacode.runtime.vps

import com.konprostart.tariffiacode.core.ssh.SshAuth
import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.core.ssh.SshHostKeyVerifier
import com.konprostart.tariffiacode.core.ssh.SshPortForward
import com.konprostart.tariffiacode.core.ssh.SshPortForwardResult
import com.konprostart.tariffiacode.core.ssh.SshSessionSharingForwarder
import com.konprostart.tariffiacode.core.ssh.SshTunnelSession
import com.konprostart.tariffiacode.core.ssh.SshTunnelSessionResult
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Identifies one tunnel: where the SSH connection goes, and where on the server it points.
 *
 * [host]/[port] are the profile's SSH endpoint, [remoteHost]/[remotePort] the service behind the
 * tunnel. The SSH endpoint is part of the key so two profiles that happen to share an id can never be
 * pooled onto one connection.
 */
data class TunnelKey(
    val profileId: String,
    val host: String,
    val port: Int,
    val remoteHost: String,
    val remotePort: Int,
)

/**
 * Shares one SSH session per profile across every tunnel that needs it.
 *
 * The app talks to the same VPS from several places at once: the OpenCode chat tunnel (the server's
 * 127.0.0.1:4096) and the live preview of whatever dev server the user started (a different port), and
 * every remote-git write opened yet another short-lived session. Each paid a full TCP connect, key
 * exchange and authentication round-trip, so the device held several equally expensive connections to
 * one host and repeated the handshake every time a tunnel was opened.
 *
 * Pooling is two-level because the two resources cost very different amounts:
 *
 *  - a **session** per profile is the expensive half - one handshake, one authentication, one
 *    keep-alive - shared by every tunnel to that profile;
 *  - a **forward** per [TunnelKey] is one channel on that session, cheap to add, and is what a caller
 *    actually holds.
 *
 * Both are reference-counted, so the session is torn down only when the last tunnel lets go. Callers
 * treat the returned forward as their own [AutoCloseable] and need not know it is shared: closing it
 * releases their share and, at zero, the tunnel goes, and at zero tunnels the session goes with it.
 *
 * Safe to call from any thread. A tunnel that died on its own is dropped and rebuilt on the next
 * acquire instead of being handed out again, and a failed connect is never cached - so an untrusted
 * host key keeps being reported until the user trusts it.
 */
class SshTunnelPool(
    private val forwarder: SshSessionSharingForwarder,
) {
    private val lock = Any()

    private class SessionEntry(
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

    private val sessions = mutableMapOf<String, SessionEntry>()

    /**
     * Open (or join) the tunnel described by [key] on the profile's SSH endpoint.
     *
     * [auth] and [verifier] are only consulted when a session actually has to be established; joining
     * an existing one costs nothing, which is the whole point.
     */
    suspend fun open(
        key: TunnelKey,
        auth: SshAuth,
        verifier: SshHostKeyVerifier,
        connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    ): SshPortForwardResult {
        join(key)?.let { return SshPortForwardResult.Listening(it) }
        return when (val established = establish(key, auth, verifier, connectTimeoutMillis)) {
            is Establishment.Failure -> established.result
            is Establishment.HostKeyUntrusted -> SshPortForwardResult.HostKeyUntrusted(established.hostKey)
            is Establishment.Established -> register(key, established)
        }
    }

    /** Returns a share of an already-open tunnel, or null when one has to be established. */
    private fun join(key: TunnelKey): SshPortForward? =
        synchronized(lock) {
            val entry = sessions[key.profileId]
            if (entry == null) {
                null
            } else if (!entry.session.isOpen) {
                // A connection that died is rebuilt on the next acquire, never handed out again.
                sessions.remove(key.profileId)
                runCatching { entry.session.close() }
                null
            } else {
                val forwardEntry = entry.forwards[key]
                if (forwardEntry != null && !forwardEntry.forward.isOpen) {
                    entry.forwards.remove(key)
                    runCatching { forwardEntry.forward.close() }
                    null
                } else if (forwardEntry != null) {
                    entry.holders++
                    forwardEntry.holders++
                    lease(key, forwardEntry.forward)
                } else {
                    null
                }
            }
        }

    /** Adds an established session/forward to the pool, or adopts one a concurrent caller won. */
    private suspend fun register(
        key: TunnelKey,
        established: Establishment.Established,
    ): SshPortForwardResult {
        val winner =
            synchronized(lock) {
                val existing = sessions[key.profileId]
                if (existing == null || !existing.session.isOpen) {
                    if (existing != null) runCatching { existing.session.close() }
                    SessionEntry(established.session).also { sessions[key.profileId] = it }
                } else {
                    existing
                }
            }
        if (winner.session === established.session) {
            return synchronized(lock) {
                val entry = sessions.getValue(key.profileId)
                val forwardEntry = entry.forwards.getOrPut(key) { ForwardEntry(established.forward) }
                entry.holders++
                forwardEntry.holders++
                SshPortForwardResult.Listening(lease(key, forwardEntry.forward))
            }
        }
        // A concurrent open established this profile's session first. Ours is redundant, so release it
        // and bind the tunnel on the session that won rather than leaving two live connections.
        runCatching { established.session.close() }
        return when (val bound = forwarder.openForwardOver(winner.session, key.remoteHost, key.remotePort)) {
            is SshPortForwardResult.Listening ->
                synchronized(lock) {
                    val entry = sessions[key.profileId]
                    val forwardEntry = entry.forwards.getOrPut(key) { ForwardEntry(bound.forward) }
                    entry.holders++
                    forwardEntry.holders++
                    SshPortForwardResult.Listening(lease(key, forwardEntry.forward))
                }
            is SshPortForwardResult.Failure -> bound
            is SshPortForwardResult.HostKeyUntrusted ->
                SshPortForwardResult.Failure("Forward reported an unexpected host-key prompt")
        }
    }

    /** A handle on one caller's share of a tunnel. */
    private fun lease(
        key: TunnelKey,
        forward: SshPortForward,
    ): SshPortForward =
        object : SshPortForward by forward {
            private val released = AtomicBoolean(false)

            override fun close() {
                if (!released.compareAndSet(false, true)) return
                release(key, forward)
            }
        }

    /** Drops one holder: the tunnel at zero, and the session when its last tunnel goes. */
    private fun release(
        key: TunnelKey,
        forward: SshPortForward,
    ) {
        synchronized(lock) {
            val entry = sessions[key.profileId] ?: return
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
                sessions.remove(key.profileId)
                runCatching { entry.session.close() }
            }
        }
    }

    /** How many callers currently hold a tunnel on this profile. Exposed for tests and diagnostics. */
    fun holders(profileId: String): Int = synchronized(lock) { sessions[profileId]?.holders ?: 0 }

    /** How many distinct tunnels are open on this profile. Exposed for tests and diagnostics. */
    fun openTunnels(profileId: String): Int = synchronized(lock) { sessions[profileId]?.forwards?.size ?: 0 }

    /** Closes every tunnel and session. Safe to call repeatedly. */
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

    /** Opens a session and one forward on it, outside the lock because it does real IO. */
    private suspend fun establish(
        key: TunnelKey,
        auth: SshAuth,
        verifier: SshHostKeyVerifier,
        connectTimeoutMillis: Long,
    ): Establishment {
        var session: SshTunnelSession? = null
        try {
            val opened =
                forwarder.openSession(
                    host = key.host,
                    port = key.port,
                    auth = auth,
                    verifier = verifier,
                    connectTimeoutMillis = connectTimeoutMillis,
                )
            session =
                when (opened) {
                    is SshTunnelSessionResult.HostKeyUntrusted -> return Establishment.HostKeyUntrusted(opened.hostKey)
                    is SshTunnelSessionResult.Failure ->
                        return Establishment.Failure(SshPortForwardResult.Failure(opened.message, opened.cause))
                    is SshTunnelSessionResult.Opened -> opened.session
                }
            return when (val bound = forwarder.openForwardOver(session, key.remoteHost, key.remotePort)) {
                is SshPortForwardResult.Listening -> Establishment.Established(session, bound.forward)
                is SshPortForwardResult.Failure -> Establishment.Failure(bound)
                // The handshake already happened, so a key prompt cannot appear here; report it rather
                // than inventing a failure type the caller would have to special-case.
                is SshPortForwardResult.HostKeyUntrusted ->
                    Establishment.Failure(SshPortForwardResult.Failure("Unexpected host-key prompt while binding a forward"))
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
