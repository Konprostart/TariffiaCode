package com.konprostart.tariffiacode.core.ssh

import kotlinx.coroutines.test.runTest
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tests for [MinaSshPortForwarder] using MINA's in-process server and a real loopback TCP service.
 *
 * The SSH server's own loopback service stands in for a VPS service (e.g. an OpenCode HTTP server bound to
 * 127.0.0.1): a local client must be able to reach it only through the forward.
 */
class MinaSshPortForwarderTest {
    private var server: SshServer? = null
    private var remoteService: ServerSocket? = null
    private var echoThread: Thread? = null

    @After
    fun tearDown() {
        runCatching { echoThread?.interrupt() }
        runCatching { remoteService?.close() }
        runCatching { server?.stop(true) }
    }

    private fun trustAllVerifier(): SshHostKeyVerifier =
        object : SshHostKeyVerifier {
            override fun verify(
                host: String,
                port: Int,
                hostKey: SshHostKey,
            ) = SshHostKeyDecision.Trusted
        }

    private fun startSshServer(password: String = "secret"): SshServer {
        val s = SshServer.setUpDefaultServer()
        s.port = 0
        val hostKey = File.createTempFile("hostkey", ".ser").apply { deleteOnExit() }
        s.keyPairProvider = SimpleGeneratorHostKeyProvider(hostKey.toPath())
        s.passwordAuthenticator =
            PasswordAuthenticator { username, pass, _ ->
                username == "tester" && pass == password
            }
        // Forwarding is what this transport does; authorize it explicitly on the test server.
        s.forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        s.start()
        server = s
        return s
    }

    /** A loopback TCP service on the "server" that echoes bytes back, uppercased. */
    private fun startEchoService(): ServerSocket {
        val socket = ServerSocket()
        socket.bind(InetSocketAddress("127.0.0.1", 0))
        remoteService = socket
        val thread =
            Thread {
                while (!socket.isClosed) {
                    val client =
                        try {
                            socket.accept()
                        } catch (_: Exception) {
                            return@Thread
                        }
                    Thread {
                        client.use { accepted ->
                            val buffer = ByteArray(1024)
                            while (true) {
                                val read = accepted.getInputStream().read(buffer)
                                if (read <= 0) break
                                val echoed = buffer.copyOf(read).decodeToString().uppercase().toByteArray()
                                accepted.getOutputStream().write(echoed)
                                accepted.getOutputStream().flush()
                            }
                        }
                    }.start()
                }
            }.apply {
                isDaemon = true
                start()
            }
        echoThread = thread
        return socket
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `a local client reaches the remote loopback service through the forward`() =
        runTest {
            val ssh = startSshServer()
            val echo = startEchoService()

            val result =
                MinaSshPortForwarder().openLocalForward(
                    host = "127.0.0.1",
                    port = ssh.port,
                    auth = SshAuth.Password("tester", "secret"),
                    verifier = trustAllVerifier(),
                    remoteHost = "127.0.0.1",
                    remotePort = echo.localPort,
                )

            assertTrue("expected Listening, got $result", result is SshPortForwardResult.Listening)
            val forward = (result as SshPortForwardResult.Listening).forward
            try {
                assertTrue(forward.isOpen)
                assertTrue("local port must be assigned", forward.localPort > 0)
                assertEquals(echo.localPort, forward.remotePort)

                Socket().use { local ->
                    local.connect(InetSocketAddress("127.0.0.1", forward.localPort), 5_000)
                    local.soTimeout = 5_000
                    local.getOutputStream().write("hello vps".toByteArray())
                    local.getOutputStream().flush()

                    val buffer = ByteArray(64)
                    val read = local.getInputStream().read(buffer)
                    assertEquals("HELLO VPS", buffer.copyOf(read).decodeToString())
                }
            } finally {
                forward.close()
            }
        }

    @Test
    fun `forward targets a configurable remote port`() =
        runTest {
            val ssh = startSshServer()
            val echo = startEchoService()

            val result =
                MinaSshPortForwarder().openLocalForward(
                    host = "127.0.0.1",
                    port = ssh.port,
                    auth = SshAuth.Password("tester", "secret"),
                    verifier = trustAllVerifier(),
                    remoteHost = "127.0.0.1",
                    remotePort = echo.localPort,
                )

            val forward = (result as SshPortForwardResult.Listening).forward
            try {
                assertEquals("127.0.0.1", forward.remoteHost)
                assertEquals(echo.localPort, forward.remotePort)
                assertEquals(SshPortForwarder.DEFAULT_REMOTE_PORT, 4096)
            } finally {
                forward.close()
            }
        }

    @Test
    fun `closing the forward closes the local listener and the session`() =
        runTest {
            val ssh = startSshServer()
            val echo = startEchoService()

            val result =
                MinaSshPortForwarder().openLocalForward(
                    host = "127.0.0.1",
                    port = ssh.port,
                    auth = SshAuth.Password("tester", "secret"),
                    verifier = trustAllVerifier(),
                    remoteHost = "127.0.0.1",
                    remotePort = echo.localPort,
                )
            val forward = (result as SshPortForwardResult.Listening).forward
            val localPort = forward.localPort
            assertTrue(forward.isOpen)

            forward.close()

            assertFalse(forward.isOpen)
            // Idempotent: a second close must not throw or reopen anything.
            forward.close()
            assertFalse(forward.isOpen)

            // The listener is gone, so a fresh connection to the forwarded port is refused.
            val refused = AtomicBoolean(false)
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", localPort), 2_000)
                    // If it did connect, the stream must not carry data (listener gone).
                    socket.soTimeout = 1_000
                    if (socket.getInputStream().read() < 0) refused.set(true)
                }
            }.onFailure { refused.set(true) }
            assertTrue("connection should be refused after close", refused.get())
        }

    @Test
    fun `unavailable remote service closes the tunnelled connection`() =
        runTest {
            val ssh = startSshServer()
            val deadPort = freePort()

            val result =
                MinaSshPortForwarder().openLocalForward(
                    host = "127.0.0.1",
                    port = ssh.port,
                    auth = SshAuth.Password("tester", "secret"),
                    verifier = trustAllVerifier(),
                    remoteHost = "127.0.0.1",
                    remotePort = deadPort,
                )

            assertTrue("expected Listening, got $result", result is SshPortForwardResult.Listening)
            val forward = (result as SshPortForwardResult.Listening).forward
            try {
                // The local listener exists, but forwarding the connection must fail because nothing
                // listens on the remote port: the tunnelled stream ends without data.
                Socket().use { local ->
                    local.connect(InetSocketAddress("127.0.0.1", forward.localPort), 5_000)
                    local.soTimeout = 5_000
                    local.getOutputStream().write("ping".toByteArray())
                    local.getOutputStream().flush()
                    val read = runCatching { local.getInputStream().read() }.getOrDefault(-1)
                    assertTrue("expected the tunnelled connection to fail, read=$read", read < 0)
                }
            } finally {
                forward.close()
            }
        }

    @Test
    fun `untrusted host key is surfaced and no forward is opened`() =
        runTest {
            val ssh = startSshServer()

            val result =
                MinaSshPortForwarder().openLocalForward(
                    host = "127.0.0.1",
                    port = ssh.port,
                    auth = SshAuth.Password("tester", "secret"),
                    verifier =
                        object : SshHostKeyVerifier {
                            override fun verify(
                                host: String,
                                port: Int,
                                hostKey: SshHostKey,
                            ) = SshHostKeyDecision.Unknown(hostKey)
                        },
                    remoteHost = "127.0.0.1",
                    remotePort = 4096,
                )

            assertTrue("expected HostKeyUntrusted, got $result", result is SshPortForwardResult.HostKeyUntrusted)
            val key = (result as SshPortForwardResult.HostKeyUntrusted).hostKey
            assertTrue(key.sha256Fingerprint.matches(Regex("^[0-9a-f]{64}$")))
        }
}
