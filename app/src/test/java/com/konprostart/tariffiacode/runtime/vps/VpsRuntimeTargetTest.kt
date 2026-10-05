package com.konprostart.tariffiacode.runtime.vps

import com.konprostart.tariffiacode.core.ssh.MinaSshPortForwarder
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshCredential
import com.konprostart.tariffiacode.data.ssh.SshCredentialCodec
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.runtime.RuntimeState
import com.sun.net.httpserver.HttpServer
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

/**
 * End-to-end tests for [VpsRuntimeTarget] using an in-process SSH server and a real loopback HTTP
 * "OpenCode" service. The service stands in for an already-installed OpenCode on a VPS: TariffiaCode must
 * reach it only through the SSH forward and then use the existing remote OpenCode backend unchanged.
 */
class VpsRuntimeTargetTest {
    private var sshServer: SshServer? = null
    private var httpServer: HttpServer? = null

    @After
    fun tearDown() {
        runCatching { httpServer?.stop(0) }
        runCatching { sshServer?.stop(true) }
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
        s.forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        s.start()
        sshServer = s
        return s
    }

    /** A minimal OpenCode HTTP server answering the one endpoint the runtime checks: `global/health`. */
    private fun startOpenCodeService(version: String = "9.9.9"): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/global/health") { exchange ->
            val body = """{"healthy":true,"version":"$version"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        httpServer = server
        return server
    }

    private fun credentialStore(password: String = "secret"): SshCredentialStore {
        val entries = mutableMapOf("ref" to SshCredentialCodec.encode(SshCredential.Password(password)))
        return SshCredentialStore(load = { entries }, save = { updated -> entries.putAll(updated) })
    }

    private fun profile(
        sshPort: Int,
        trusted: String? = null,
    ) = SshProfile(
        id = "vps-1",
        name = "My VPS",
        host = "127.0.0.1",
        port = sshPort,
        username = "tester",
        authType = SshAuthType.PASSWORD,
        credentialRef = "ref",
        trustedHostKeyFingerprint = trusted,
    )

    private fun target(
        store: SshCredentialStore,
        remotePort: Int = VpsRuntimeConnector.DEFAULT_REMOTE_PORT,
    ) = VpsRuntimeTarget(VpsRuntimeConnector(MinaSshPortForwarder(), store), remotePort = remotePort)

    /** Establishes trust for the test server so the host-key check passes on the next connect. */
    private suspend fun VpsRuntimeTarget.trustServer(sshPort: Int): SshProfile {
        selectProfile(profile(sshPort))
        connect()
        val pending = pendingHostKey.value
        require(pending != null) { "expected a pending host key" }
        val trusted = trustHostKey(profile(sshPort), pending)
        selectProfile(trusted)
        return trusted
    }

    @Test
    fun `connect forwards SSH and uses the existing OpenCode backend for health`() =
        runTest {
            val ssh = startSshServer()
            startOpenCodeService(version = "9.9.9")
            val target = target(credentialStore())

            target.trustServer(ssh.port)
            val result = target.connect()

            assertTrue("expected connected, got $result", result.isSuccess)
            assertEquals("9.9.9", result.getOrNull()?.version)
            assertTrue(target.state.value is RuntimeState.Connected)
            assertTrue(target.isForwardOpen)
            target.disconnect()
        }

    @Test
    fun `unavailable OpenCode on the VPS surfaces a failure state`() =
        runTest {
            val ssh = startSshServer()
            val deadPort = ServerSocket(0).use { it.localPort }
            val target = target(credentialStore(), remotePort = deadPort)

            target.trustServer(ssh.port)
            val result = target.connect()

            assertTrue("expected failure, got $result", result.isFailure)
            assertTrue("expected Failed state, got ${target.state.value}", target.state.value is RuntimeState.Failed)
        }

    @Test
    fun `disconnect closes the forward and the runtime can reconnect`() =
        runTest {
            val ssh = startSshServer()
            startOpenCodeService()
            val target = target(credentialStore())

            target.trustServer(ssh.port)
            assertTrue(target.connect().isSuccess)
            assertTrue(target.isForwardOpen)

            target.disconnect()
            assertFalse("forward must be closed on disconnect", target.isForwardOpen)
            assertTrue(target.state.value is RuntimeState.Disconnected)

            // Reconnecting proves the SSH client/session were released and can be re-established.
            assertTrue("expected reconnect to succeed", target.connect().isSuccess)
            target.disconnect()
        }

    @Test
    fun `a profile with no stored credential reports failure without opening a forward`() =
        runTest {
            val ssh = startSshServer()
            val emptyStore = SshCredentialStore(load = { emptyMap() }, save = {})
            val target = target(emptyStore)
            target.selectProfile(profile(ssh.port).copy(trustedHostKeyFingerprint = "0".repeat(64)))

            val result = target.connect()

            assertTrue(result.isFailure)
            assertFalse(target.isForwardOpen)
        }

    @Test
    fun `credentials and profiles never render their secrets`() {
        val secret = "hunter2-secret"
        val credential = SshCredential.Password(secret)
        assertFalse(credential.toString().contains(secret))

        val entries = mutableMapOf("ref" to SshCredentialCodec.encode(credential))
        val store = SshCredentialStore(load = { entries }, save = {})
        assertEquals(credential, store.credential("ref"))

        // The profile model carries no secret at all.
        assertFalse(profile(22).toString().contains(secret))
    }
}
