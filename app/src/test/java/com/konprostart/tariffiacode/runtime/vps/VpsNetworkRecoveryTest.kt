package com.konprostart.tariffiacode.runtime.vps

import com.konprostart.tariffiacode.core.network.NetworkStatus
import com.konprostart.tariffiacode.core.ssh.MinaSshPortForwarder
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshCredential
import com.konprostart.tariffiacode.data.ssh.SshCredentialCodec
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.runtime.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Recovery from a network handover: the phone moves to a different network and the SSH session it was
 * carrying is stranded without either end noticing, so the tunnel has to be rebuilt on its own.
 *
 * Runs against a real MINA server and a real loopback OpenCode, like the lifecycle tests, because what
 * is under test is what a real session does when it stops answering.
 *
 * These use a real dispatcher rather than the test scheduler on purpose. Opening a forward suspends in
 * `withContext(Dispatchers.IO)` for a real SSH handshake, which virtual time cannot wait for, so the
 * assertions wait on observable state instead.
 */
class VpsNetworkRecoveryTest {
    private var sshServer: SshServer? = null
    private var openCode: RecordingOpenCode? = null
    private var recoveryScope: CoroutineScope? = null

    @After
    fun tearDown() {
        runCatching { recoveryScope?.cancel() }
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

    /** A target wired for recovery. A 0 ms initial delay makes the rebuild happen on the first tick. */
    private fun target(
        sshPort: Int,
        network: MutableStateFlow<NetworkStatus>,
        initialDelayMillis: Long = 0L,
    ): VpsRuntimeTarget {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        recoveryScope = scope
        return VpsRuntimeTarget(
            connector = VpsRuntimeConnector(MinaSshPortForwarder(), credentialStore()),
            remotePort = openCode!!.port,
            recovery =
                VpsRecovery(
                    scope = scope,
                    networkStatus = network,
                    backoff = ReconnectBackoff(initialDelayMillis = initialDelayMillis, jitterTenths = 0),
                ),
        )
    }

    /** Waits for [condition] against the wall clock, with a message that says what never happened. */
    private fun awaitTrue(
        what: String,
        timeoutMillis: Long = 15_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail("timed out after ${timeoutMillis}ms waiting for: $what")
    }

    @Test
    fun `a network handover rebuilds the tunnel without the user doing anything`() =
        runBlocking {
            val ssh = startSshServer()
            val service = RecordingOpenCode().also { openCode = it }
            val network = MutableStateFlow<NetworkStatus>(NetworkStatus.Connected("wifi"))
            val target = target(ssh.port, network)
            target.trustAndConnect(ssh.port)
            assertTrue(target.state.value is RuntimeState.Connected)
            val probesBefore = service.healthRequests.get()

            // The handover the radio never reports as a disconnect: the old socket is gone, but the
            // app's own bookkeeping still says connected.
            network.value = NetworkStatus.Connected("cell")

            // Proving a rebuild rather than "still looks fine": only a fresh tunnel asks the server
            // about its health again.
            awaitTrue("the tunnel to be re-probed after the handover") {
                service.healthRequests.get() > probesBefore
            }
            awaitTrue("the rebuilt tunnel to report connected") {
                target.state.value is RuntimeState.Connected
            }
            assertTrue("expected a live forward after the handover", target.isForwardOpen)
            target.disconnect()
        }

    @Test
    fun `the recovery is a real reconnect - a dead service is noticed`() =
        runBlocking {
            val ssh = startSshServer()
            val service = RecordingOpenCode().also { openCode = it }
            val network = MutableStateFlow<NetworkStatus>(NetworkStatus.Connected("wifi"))
            val target = target(ssh.port, network)
            target.trustAndConnect(ssh.port)
            assertTrue(target.state.value is RuntimeState.Connected)

            // With the service gone, the only way the target can learn that is by rebuilding the
            // tunnel and asking again - which is exactly what the handover triggers.
            service.close()
            network.value = NetworkStatus.Connected("cell")

            awaitTrue("recovery to re-probe and surface the failure") {
                target.state.value is RuntimeState.Failed
            }
            target.disconnect()
        }

    @Test
    fun `an explicit disconnect wins over a recovery already under way`() =
        runBlocking {
            val ssh = startSshServer()
            val service = RecordingOpenCode().also { openCode = it }
            val network = MutableStateFlow<NetworkStatus>(NetworkStatus.Connected("wifi"))
            // A backoff long enough that the loop is parked in its first wait when the user acts.
            val target = target(ssh.port, network, initialDelayMillis = 2_000L)
            target.trustAndConnect(ssh.port)

            network.value = NetworkStatus.Connected("cell")
            Thread.sleep(300)
            target.disconnect()
            val probesAfterDisconnect = service.healthRequests.get()

            // Wait past the backoff, then a margin for a loopback handshake that a recovery would
            // have needed. Long enough that a slow runner cannot turn this into a false pass.
            Thread.sleep(6_000)

            assertTrue(
                "recovery must not resurrect a connection the user ended, got ${target.state.value}",
                target.state.value is RuntimeState.Disconnected,
            )
            assertFalse("recovery must not leave a tunnel open behind the user", target.isForwardOpen)
            assertTrue(
                "recovery must not re-probe a connection the user ended",
                service.healthRequests.get() == probesAfterDisconnect,
            )
        }

    /** Minimal OpenCode HTTP server: `global/health`, so a tunnel re-probe can succeed. */
    private class RecordingOpenCode : AutoCloseable {
        private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        private val started = CountDownLatch(1)

        /** How many health probes reached the server; a rebuilt tunnel shows up as a new one. */
        val healthRequests = AtomicInteger(0)

        private val accepting =
            Thread {
                started.countDown()
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
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                // Anything that is not the health route is a 404, so a probe aimed at the wrong
                // endpoint shows up as a failure instead of a false positive.
                val isHealth = requestLine.contains("/global/health")
                if (isHealth) healthRequests.incrementAndGet()
                val payload =
                    if (isHealth) {
                        """{"healthy":true,"version":"9.9.9"}"""
                    } else {
                        ""
                    }
                val status = if (isHealth) "200 OK" else "404 Not Found"
                val response =
                    "HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${payload.length}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(response.toByteArray())
                    write(payload.toByteArray())
                    flush()
                }
            }
        }

        override fun close() {
            runCatching { started.await(1, TimeUnit.SECONDS) }
            runCatching { server.close() }
            runCatching { accepting.interrupt() }
        }
    }
}
