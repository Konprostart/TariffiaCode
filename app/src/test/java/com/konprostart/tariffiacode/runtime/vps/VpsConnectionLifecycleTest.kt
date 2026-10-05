package com.konprostart.tariffiacode.runtime.vps

import com.konprostart.tariffiacode.core.ssh.MinaSshPortForwarder
import com.konprostart.tariffiacode.data.remote.RemoteProject
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshCredential
import com.konprostart.tariffiacode.data.ssh.SshCredentialCodec
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.runtime.RuntimeState
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
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Focused tests for the VPS connection lifecycle: connect, failure, disconnect, reconnect, repeated
 * disconnect, failed-connection cleanup, mapped directory stability, and absence of resource leaks.
 */
class VpsConnectionLifecycleTest {
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

    private suspend fun VpsRuntimeTarget.trustServer(sshPort: Int): SshProfile {
        selectProfile(profile(sshPort))
        connect()
        val pending = pendingHostKey.value ?: error("expected pending host key")
        val trusted = trustHostKey(profile(sshPort), pending)
        selectProfile(trusted)
        return trusted
    }

    private fun target(
        store: SshCredentialStore,
        remotePort: Int,
    ) = VpsRuntimeTarget(VpsRuntimeConnector(MinaSshPortForwarder(), store), remotePort = remotePort)

    @Test
    fun `connect success leaves a connected state with an open forward`() =
        runTest {
            val ssh = startSshServer()
            val service = RecordingOpenCode().also { openCode = it }
            val target = target(credentialStore(), service.port)
            target.trustServer(ssh.port)

            val result = target.connect()

            assertTrue(result.isSuccess)
            assertTrue(target.state.value is RuntimeState.Connected)
            assertTrue(target.isForwardOpen)
            target.disconnect()
        }

    @Test
    fun `connect failure leaves a clean non-connected state without a forward`() =
        runTest {
            val ssh = startSshServer()
            val deadPort = ServerSocket(0).use { it.localPort }
            val target = target(credentialStore(), deadPort)
            target.trustServer(ssh.port)

            val result = target.connect()

            assertTrue(result.isFailure)
            assertTrue(target.state.value is RuntimeState.Failed)
            assertFalse("a failed connection must not leave a forward open", target.isForwardOpen)
        }

    @Test
    fun `disconnect closes the forward and is safe to repeat`() =
        runTest {
            val ssh = startSshServer()
            val service = RecordingOpenCode().also { openCode = it }
            val target = target(credentialStore(), service.port)
            target.trustServer(ssh.port)
            assertTrue(target.connect().isSuccess)
            assertTrue(target.isForwardOpen)

            target.disconnect()
            assertFalse(target.isForwardOpen)
            assertTrue(target.state.value is RuntimeState.Disconnected)

            // Repeated disconnect must not throw or leak.
            target.disconnect()
            target.disconnect()
            assertFalse(target.isForwardOpen)
            assertTrue(target.state.value is RuntimeState.Disconnected)
        }

    @Test
    fun `reconnect creates a fresh working connection`() =
        runTest {
            val ssh = startSshServer()
            val service = RecordingOpenCode().also { openCode = it }
            val target = target(credentialStore(), service.port)
            target.trustServer(ssh.port)
            assertTrue(target.connect().isSuccess)

            val result = target.reconnect()

            assertTrue(result.isSuccess)
            assertTrue(target.state.value is RuntimeState.Connected)
            assertTrue(target.isForwardOpen)
            target.disconnect()
        }

    @Test
    fun `reconnect after a failed connection recovers`() =
        runTest {
            val ssh = startSshServer()
            val deadPort = ServerSocket(0).use { it.localPort }
            // First target points at a dead OpenCode port; the second (same SSH) is healthy.
            val failing = target(credentialStore(), deadPort)
            failing.trustServer(ssh.port)
            assertTrue(failing.connect().isFailure)
            assertFalse(failing.isForwardOpen)

            val service = RecordingOpenCode().also { openCode = it }
            val healthy = target(credentialStore(), service.port)
            healthy.selectProfile(profile(ssh.port, trusted = failing.selectedProfile.value?.trustedHostKeyFingerprint))
            assertTrue(healthy.connect().isSuccess)
            assertTrue(healthy.isForwardOpen)
            healthy.disconnect()
        }

    @Test
    fun `mapped remote project directory survives a reconnect`() =
        runTest {
            val ssh = startSshServer()
            val service = RecordingOpenCode().also { openCode = it }
            val target = target(credentialStore(), service.port)
            target.trustServer(ssh.port)
            target.selectRemoteProject(
                RemoteProject(id = "m1", projectRef = "p", sshProfileId = "vps-1", remotePath = "/root/mapped"),
            )
            assertTrue(target.connect().isSuccess)

            target.reconnect()
            service.createdSessionDirectories.clear()
            target.createSession(title = null, directory = null)

            assertEquals(listOf("/root/mapped"), service.createdSessionDirectories)
            target.disconnect()
        }

    @Test
    fun `no credential material appears in lifecycle state messages`() =
        runTest {
            val secret = "hunter2-secret"
            val entries = mutableMapOf("ref" to SshCredentialCodec.encode(SshCredential.Password(secret)))
            val store = SshCredentialStore(load = { entries }, save = { updated -> entries.putAll(updated) })
            val deadPort = ServerSocket(0).use { it.localPort }
            val ssh = startSshServer(password = secret)
            val target = target(store, deadPort)
            target.trustServer(ssh.port)

            target.connect()

            val state = target.state.value
            val text = state.toString()
            assertFalse(text.contains(secret))
            assertTrue(state is RuntimeState.Failed)
        }

    /** Minimal OpenCode HTTP server: `global/health` plus `POST /session` recording the directory. */
    private class RecordingOpenCode : AutoCloseable {
        private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val createdSessionDirectories = CopyOnWriteArrayList<String?>()

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
                val requestLine = reader.readLine() ?: return
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                if (contentLength > 0) reader.read(CharArray(contentLength))
                val (status, body) =
                    when {
                        requestLine.contains("/global/health") -> 200 to """{"healthy":true,"version":"9.9.9"}"""
                        requestLine.startsWith("POST") && requestLine.contains("/session") -> {
                            createdSessionDirectories += directoryOf(requestLine)
                            200 to """{"id":"s1","title":"","time":{"created":1}}"""
                        }
                        else -> 404 to ""
                    }
                val payload = body.toByteArray()
                val response =
                    "HTTP/1.1 $status OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(response.toByteArray())
                    write(payload)
                    flush()
                }
            }
        }

        private fun directoryOf(requestLine: String): String? =
            requestLine
                .substringAfter('?', "")
                .substringBefore(' ')
                .split('&')
                .firstOrNull { it.startsWith("directory=") }
                ?.substringAfter("directory=")
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }

        override fun close() {
            runCatching { server.close() }
            runCatching { accepting.interrupt() }
        }
    }
}
