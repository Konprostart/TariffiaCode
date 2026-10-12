package com.konprostart.tariffiacode.runtime.vps

import com.konprostart.tariffiacode.core.network.NetworkStatus
import com.konprostart.tariffiacode.core.ssh.MinaSshPortForwarder
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshCredential
import com.konprostart.tariffiacode.data.ssh.SshCredentialCodec
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.runtime.RuntimeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Recovery from a network handover: the phone moves to a different network and the SSH session it was
 * carrying is stranded without either end noticing, so the tunnel has to be rebuilt on its own.
 *
 * Runs against a real MINA server and a real loopback OpenCode, like the lifecycle tests, because what
 * is under test is what a real session does when it stops answering.
 */
class VpsNetworkRecoveryTest {
    private var sshServer: SshServer? = null
    private var openCode: RecordingOpenCode? = null

    @After
    fun tearDown() {
        runCatching { openCode?.close() }
        runCatching { sshServer?.stop(true) }
    }

    private fun startSshServer(password: String = "secret"): SshServer {
        val s = SshServer.setUpDefaultServer()
        s.port = 0
        val hostKey = File.createTempFile("hostkey", ".ser").apply { deleteOnExit() }
        s.keyPairProvider = SimpleGeneratorHostKeyProvider(hostKey.toPath())
        s.passwordAuthenticator = PasswordAuthenticator { username, pass, _ -> username == "tester" && pass == password }
        s.forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        s.start()
        sshServer = s
        return s
    }

    private fun credentialStore(): SshCredentialStore {
        val entries = mutableMapOf("ref" to SshCredentialCodec.encode(SshCredential.Password("secret")))
        return SshCredentialStore(load = { entries }, save = { updated -> entries.putAll(updated) })
    }

    private fun profile(sshPort: Int): SshProfile =
        SshProfile(
            id = "vps-1",
            name = "My VPS",
            host = "127.0.0.1",
            port = sshPort,
            username = "tester",
            authType = SshAuthType.PASSWORD,
            credentialRef = "ref",
        )

    /** Connects and trusts the test server's key, which the first connect always requires. */
    private suspend fun VpsRuntimeTarget.trustAndConnect(sshPort: Int) {
        selectProfile(profile(sshPort))
        connect()
        val pending = pendingHostKey.value ?: error("expected pending host key")
        selectProfile(trustHostKey(profile(sshPort), pending))
        assertTrue("setup connect failed", connect().isSuccess)
    }

    /** A target wired for recovery, with no waiting so it is observable as soon as time advances. */
    private fun TestScope.target(network: MutableStateFlow<NetworkStatus>): VpsRuntimeTarget =
        VpsRuntimeTarget(
            connector = VpsRuntimeConnector(MinaSshPortForwarder(), credentialStore()),
            remotePort = openCode!!.port,
            recoveryScope = backgroundScope,
            networkStatus = network,
            backoff = ReconnectBackoff(initialDelayMillis = 1L, maxDelayMillis = 1L, jitterTenths = 0),
        )

    @Test
    fun `a network handover rebuilds the tunnel without the user doing anything`() =
        runTest {
            val ssh = startSshServer()
            val service = RecordingOpenCode().also { openCode = it }
            val network = MutableStateFlow<NetworkStatus>(NetworkStatus.Connected("wifi"))
            val target = target(network)
            target.trustAndConnect(ssh.port)
            assertTrue(target.state.value is RuntimeState.Connected)

            network.value = NetworkStatus.Connected("cell")
            advanceUntilIdle()

            assertTrue(
                "expected the tunnel to still be usable after a handover, got ${target.state.value}",
                target.state.value is RuntimeState.Connected,
            )
            assertTrue("expected a live forward after the handover", target.isForwardOpen)
            target.disconnect()
        }

    @Test
    fun `the recovery is a real reconnect - a dead service is noticed`() =
        runTest {
            val ssh = startSshServer()
            val service = RecordingOpenCode().also { openCode = it }
            val network = MutableStateFlow<NetworkStatus>(NetworkStatus.Connected("wifi"))
            val target = target(network)
            target.trustAndConnect(ssh.port)
            assertTrue(target.state.value is RuntimeState.Connected)

            // With the service gone, the only way the target can learn that is by rebuilding the
            // tunnel and asking again - which is exactly what the handover triggers.
            service.close()
            network.value = NetworkStatus.Connected("cell")
            advanceUntilIdle()

            assertTrue(
                "recovery should have re-probed and surfaced the failure, got ${target.state.value}",
                target.state.value is RuntimeState.Failed,
            )
            target.disconnect()
        }

    @Test
    fun `an explicit disconnect wins over a pending recovery`() =
        runTest {
            val ssh = startSshServer()
            val service = RecordingOpenCode().also { openCode = it }
            val network = MutableStateFlow<NetworkStatus>(NetworkStatus.Connected("wifi"))
            val target = target(network)
            target.trustAndConnect(ssh.port)

            network.value = NetworkStatus.Connected("cell")
            target.disconnect()
            advanceUntilIdle()

            assertTrue(
                "recovery must not resurrect a connection the user ended, got ${target.state.value}",
                target.state.value is RuntimeState.Disconnected,
            )
        }

    /** Minimal OpenCode HTTP server: `global/health`, so a tunnel re-probe can succeed. */
    private class RecordingOpenCode : AutoCloseable {
        private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }

        private val accepting =
            Thread {
                while (!server.isClosed) {
                    val client =
                        try {
                            server.accept()
                        } catch (_: Exception) {
                            return@Thread
                        }
                    Thread { handle(client) }.apply { isDaemon = true }.start()
                }
            }.apply {
                isDaemon = true
                start()
            }

        val port: Int get() = server.localPort

        private fun handle(client: Socket) {
            client.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val payload = """{"healthy":true,"version":"9.9.9"}""".toByteArray()
                val response =
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(response.toByteArray())
                    write(payload)
                    flush()
                }
            }
        }

        override fun close() {
            runCatching { server.close() }
            runCatching { accepting.interrupt() }
        }
    }
}
