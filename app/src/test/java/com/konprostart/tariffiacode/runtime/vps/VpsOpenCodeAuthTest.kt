package com.konprostart.tariffiacode.runtime.vps

import com.konprostart.tariffiacode.core.ssh.SshPortForward
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The check that decides whether a VPS OpenCode endpoint may be handed to a user's agent.
 *
 * Everything runs over a loopback [MockWebServer] standing in for the far end of the SSH forward,
 * so this is the product's own decision logic against real HTTP, with no VPS and no emulator. The
 * fork it guards is the one that matters: Android shares `127.0.0.1` between apps, so an
 * unauthenticated endpoint here is reachable by anything else installed.
 */
class VpsOpenCodeAuthTest {
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun forward(): SshPortForward = FakePortForward(server.hostName, server.port)

    @Test
    fun `a server that accepts an unauthenticated request is refused`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"healthy":true}"""))

        assertEquals(VpsOpenCodeAuth.Outcome.Unauthenticated, verify())
    }

    @Test
    fun `no stored password is refused without a single request`() {
        // Nothing is sent at all: there is no credential to send, and probing an open endpoint
        // without one is how the unverified case used to look like a working one.
        assertEquals(VpsOpenCodeAuth.Outcome.Unauthenticated, VpsOpenCodeAuth.verify(forward(), "opencode", null))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a blank password is treated as no password`() {
        assertEquals(VpsOpenCodeAuth.Outcome.Unauthenticated, VpsOpenCodeAuth.verify(forward(), "opencode", "   "))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a protected server that accepts our password is authenticated`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"healthy":true}"""))

        assertEquals(VpsOpenCodeAuth.Outcome.Authenticated, verify())
    }

    @Test
    fun `the unauthenticated probe carries no credentials and the second carries basic auth`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200))

        verify()

        val anonymous = server.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("GET", anonymous.method)
        assertEquals(null, anonymous.getHeader("Authorization"))

        val authenticated = server.takeRequest(2, TimeUnit.SECONDS)
        val header = authenticated.getHeader("Authorization")
        assertTrue("expected Basic auth, got $header", header?.startsWith("Basic ") == true)
    }

    @Test
    fun `a protected server that rejects our password is a failure, not an open endpoint`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))

        val outcome = verify()

        assertTrue("expected Failed, got $outcome", outcome is VpsOpenCodeAuth.Outcome.Failed)
        assertTrue(
            "the message must name the actual cause",
            (outcome as VpsOpenCodeAuth.Outcome.Failed).message.contains("OPENCODE_SERVER_PASSWORD"),
        )
    }

    @Test
    fun `an unreachable server is a failure rather than an accusation`() {
        // The forward is up but nothing listens. Refusing to route because the VPS is down would be a
        // worse failure mode than the one being fixed, and the app has its own state for it.
        val deadPort = ServerSocketHolder.freePort()

        val outcome = VpsOpenCodeAuth.verify(FakePortForward("127.0.0.1", deadPort), "opencode", "secret")

        assertTrue("expected Failed, got $outcome", outcome is VpsOpenCodeAuth.Outcome.Failed)
    }

    @Test
    fun `an unexpected status on the open probe is not mistaken for an open server`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val outcome = verify()

        assertTrue("expected Failed, got $outcome", outcome is VpsOpenCodeAuth.Outcome.Failed)
    }

    private fun verify(): VpsOpenCodeAuth.Outcome = VpsOpenCodeAuth.verify(forward(), "opencode", "correct-horse")

    /** The only thing the check needs from a forward is the loopback address it landed on. */
    private class FakePortForward(
        override val localHost: String,
        override val localPort: Int,
    ) : SshPortForward {
        override val remoteHost: String = "127.0.0.1"

        override val remotePort: Int = 4096

        override fun close() = Unit
    }

    private object ServerSocketHolder {
        fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }
    }
}
