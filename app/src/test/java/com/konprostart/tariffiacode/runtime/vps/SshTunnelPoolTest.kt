package com.konprostart.tariffiacode.runtime.vps

import com.konprostart.tariffiacode.core.ssh.SshAuth
import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.core.ssh.SshHostKeyDecision
import com.konprostart.tariffiacode.core.ssh.SshHostKeyVerifier
import com.konprostart.tariffiacode.core.ssh.SshPortForward
import com.konprostart.tariffiacode.core.ssh.SshPortForwardResult
import com.konprostart.tariffiacode.core.ssh.SshSessionSharingForwarder
import com.konprostart.tariffiacode.core.ssh.SshTunnelSession
import com.konprostart.tariffiacode.core.ssh.SshTunnelSessionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pool's contract: one session per endpoint shared by every tunnel, a forward per tunnel, and both
 * released only when their last holder lets go.
 *
 * Driven through a fake transport so the counts are exact - a real MINA server cannot tell how many
 * connections were opened, which is the only thing under test here.
 */
class SshTunnelPoolTest {
    private class FakeForward(
        private val port: Int,
        override val remotePort: Int,
    ) : SshPortForward {
        override val localHost = "127.0.0.1"
        override val localPort = port
        override val remoteHost = "127.0.0.1"
        override var isOpen = true
        var closed = false

        override fun close() {
            closed = true
            isOpen = false
        }
    }

    private class FakeSession : SshTunnelSession {
        override var isOpen = true
        var closed = false

        override fun close() {
            closed = true
            isOpen = false
        }
    }

    private class FakeTransport(
        private val sessionResult: (Int) -> SshTunnelSessionResult = { FakeSession().let(SshTunnelSessionResult::Opened) },
        private val forwardResult: (String, Int) -> SshPortForwardResult = { host, port ->
            SshPortForwardResult.Listening(FakeForward(port = 40000 + port, remotePort = port))
        },
    ) : SshSessionSharingForwarder {
        var sessionOpens = 0
        var forwardOpens = 0
        val sessions = mutableListOf<FakeSession>()

        /** Every forward handed out, so a test can let one die the way a dropped channel does. */
        val forwards = mutableListOf<FakeForward>()

        override suspend fun openSession(
            host: String,
            port: Int,
            auth: SshAuth,
            verifier: SshHostKeyVerifier,
            connectTimeoutMillis: Long,
        ): SshTunnelSessionResult {
            sessionOpens++
            val result = sessionResult(sessionOpens)
            (result as? SshTunnelSessionResult.Opened)?.let { session -> (session.session as? FakeSession)?.let(sessions::add) }
            return result
        }

        override suspend fun openForwardOver(
            session: SshTunnelSession,
            remoteHost: String,
            remotePort: Int,
        ): SshPortForwardResult {
            forwardOpens++
            val result = forwardResult(remoteHost, remotePort)
            (result as? SshPortForwardResult.Listening)?.let { (it.forward as? FakeForward)?.let(forwards::add) }
            return result
        }

        override suspend fun openLocalForward(
            host: String,
            port: Int,
            auth: SshAuth,
            verifier: SshHostKeyVerifier,
            remoteHost: String,
            remotePort: Int,
            connectTimeoutMillis: Long,
        ): SshPortForwardResult = forwardResult(remoteHost, remotePort)
    }

    private val verifier =
        object : SshHostKeyVerifier {
            override fun verify(
                host: String,
                port: Int,
                hostKey: SshHostKey,
            ): SshHostKeyDecision = SshHostKeyDecision.Trusted
        }

    private val auth = SshAuth.Password("tester", "secret")

    private fun key(port: Int, profileId: String = "vps-1") =
        TunnelKey(profileId = profileId, host = "127.0.0.1", port = 22, remoteHost = "127.0.0.1", remotePort = port)

    private suspend fun SshTunnelPool.listen(k: TunnelKey): SshPortForward =
        (open(k, auth, verifier) as SshPortForwardResult.Listening).forward

    @Test
    fun `two tunnels on one profile share a single session`() =
        runTest {
            val transport = FakeTransport()
            val pool = SshTunnelPool(transport)

            pool.listen(key(4096))
            pool.listen(key(5173))

            assertEquals("one handshake should serve both tunnels", 1, transport.sessionOpens)
            assertEquals("each tunnel is its own channel", 2, transport.forwardOpens)
            assertEquals(2, pool.holders("vps-1"))
        }

    @Test
    fun `a second holder of the same tunnel reuses the forward itself`() =
        runTest {
            val transport = FakeTransport()
            val pool = SshTunnelPool(transport)

            val first = pool.listen(key(4096))
            val second = pool.listen(key(4096))

            // No second channel was opened, and releasing one holder left the shared forward up.
            assertEquals(1, transport.sessionOpens)
            assertEquals(1, transport.forwardOpens)
            assertEquals(2, pool.holders("vps-1"))
            first.close()
            assertFalse("one release must not tear down a tunnel another holder is using", transport.forwards[0].closed)
            second.close()
            assertTrue(transport.forwards[0].closed)
        }

    @Test
    fun `the session outlives a tunnel until the last holder lets go`() =
        runTest {
            val transport = FakeTransport()
            val pool = SshTunnelPool(transport)

            val chat = pool.listen(key(4096))
            val preview = pool.listen(key(5173))

            chat.close()

            assertFalse("the session must survive while the preview still holds a tunnel", transport.sessions[0].closed)
            assertEquals(1, pool.holders("vps-1"))
            assertEquals(1, pool.openTunnels("vps-1"))

            preview.close()

            assertTrue("the last release must close the session", transport.sessions[0].closed)
            assertEquals(0, pool.holders("vps-1"))
            assertEquals(0, pool.openTunnels("vps-1"))
        }

    @Test
    fun `a tunnel held twice closes only on the second release`() =
        runTest {
            val transport = FakeTransport()
            val pool = SshTunnelPool(transport)

            val first = pool.listen(key(4096))
            val second = pool.listen(key(4096))
            val shared = transport.forwardOpens

            first.close()
            assertFalse("one release must not tear down a tunnel another holder is using", transport.sessions[0].closed)

            second.close()
            assertTrue(transport.sessions[0].closed)
            assertEquals(shared, transport.forwardOpens)
        }

    @Test
    fun `closing a lease twice releases only once`() =
        runTest {
            val transport = FakeTransport()
            val pool = SshTunnelPool(transport)
            val preview = pool.listen(key(5173))

            preview.close()
            preview.close()

            assertEquals(0, pool.holders("vps-1"))
        }

    @Test
    fun `different profiles never share a session`() =
        runTest {
            val transport = FakeTransport()
            val pool = SshTunnelPool(transport)

            pool.listen(key(4096, profileId = "vps-1"))
            pool.listen(key(4096, profileId = "vps-2"))

            assertEquals("each profile needs its own connection", 2, transport.sessionOpens)
        }

    @Test
    fun `a failed connect is never cached, so an untrusted key keeps being reported`() =
        runTest {
            val key = SshHostKey("ssh-ed25519", "bb".repeat(32))
            val transport = FakeTransport(sessionResult = { SshTunnelSessionResult.HostKeyUntrusted(key) })
            val pool = SshTunnelPool(transport)

            val first = pool.open(key(4096), auth, verifier)
            val second = pool.open(key(4096), auth, verifier)

            assertTrue(first is SshPortForwardResult.HostKeyUntrusted)
            assertTrue(second is SshPortForwardResult.HostKeyUntrusted)
            assertEquals("a refused handshake must be retried, not cached", 2, transport.sessionOpens)
        }

    @Test
    fun `a dead session is rebuilt instead of handed out again`() =
        runTest {
            val transport = FakeTransport()
            val pool = SshTunnelPool(transport)

            pool.listen(key(4096))
            transport.sessions[0].isOpen = false

            pool.listen(key(4096))

            assertEquals("a dead connection must be re-established", 2, transport.sessionOpens)
            assertEquals("a dead connection means a new channel too", 2, transport.forwardOpens)
        }

    @Test
    fun `a dead tunnel on a live session is rebound without re-handshaking`() =
        runTest {
            val transport = FakeTransport()
            val pool = SshTunnelPool(transport)
            pool.listen(key(4096))
            // A second tunnel keeps the shared session alive while the first one dies.
            pool.listen(key(5173))

            val dying = transport.forwards[0]
            dying.isOpen = false // the channel dropped without being closed

            pool.listen(key(4096))

            assertEquals("the session is still alive, so no new handshake is needed", 1, transport.sessionOpens)
            assertEquals("the dead channel is replaced", 3, transport.forwardOpens)
            assertTrue("the dead channel is closed off", dying.closed)
        }

    @Test
    fun `a connection that cannot bind a tunnel is released`() =
        runTest {
            // The handshake and authentication succeeded, so this connection is real and expensive;
            // failing to bind must not leave it behind.
            val transport =
                FakeTransport(
                    forwardResult = { _, _ -> SshPortForwardResult.Failure("direct-tcpip refused") },
                )
            val pool = SshTunnelPool(transport)

            val result = pool.open(key(4096), auth, verifier)

            assertTrue(result is SshPortForwardResult.Failure)
            assertEquals("the connection must be handed back", 1, transport.sessions.size)
            assertTrue(transport.sessions.all { it.closed })
            assertEquals(0, pool.holders("vps-1"))
        }

    @Test
    fun `a profile whose endpoint changed never reuses the old connection`() =
        runTest {
            // Same profile id, different host: the old connection was trusted for the old host, so
            // reusing it would reach the wrong machine without the verifier being consulted again.
            val transport = FakeTransport()
            val pool = SshTunnelPool(transport)

            pool.listen(key(4096))
            pool.listen(key(4096).copy(host = "other.example", port = 2222))

            assertEquals("a new endpoint needs its own connection", 2, transport.sessionOpens)
            assertEquals("both are still held", 2, pool.holders("vps-1"))
        }

    @Test
    fun `closeAll releases every tunnel and session`() =
        runTest {
            val transport = FakeTransport()
            val pool = SshTunnelPool(transport)
            pool.listen(key(4096))
            pool.listen(key(5173))

            pool.closeAll()

            assertEquals(0, pool.holders("vps-1"))
            assertTrue(transport.sessions.all { it.closed })
        }
}
