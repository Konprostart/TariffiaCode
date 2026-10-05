package com.konprostart.tariffiacode.runtime.vps

import com.konprostart.tariffiacode.core.ssh.MinaSshPortForwarder
import com.konprostart.tariffiacode.data.remote.RemoteProject
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshCredential
import com.konprostart.tariffiacode.data.ssh.SshCredentialCodec
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import kotlinx.coroutines.test.runTest
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Proves the Remote Project mapping is honoured end-to-end: when a mapping is applied, an OpenCode session
 * created through the VPS runtime is asked for the mapped remote directory. Uses an in-process SSH server
 * and a real loopback HTTP "OpenCode" that records the `directory` query it is sent.
 */
class VpsRuntimeTargetRemoteProjectTest {
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

    private fun profile(sshPort: Int, trusted: String? = null) =
        SshProfile(
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

    @Test
    fun `creating a session uses the mapped remote directory`() =
        runTest {
            val ssh = startSshServer()
            val service = RecordingOpenCode().also { openCode = it }
            val target =
                VpsRuntimeTarget(VpsRuntimeConnector(MinaSshPortForwarder(), credentialStore()), remotePort = service.port)

            target.trustServer(ssh.port)
            target.selectRemoteProject(
                RemoteProject(
                    id = "m1",
                    projectRef = "Konprostart/TariffiaCode",
                    sshProfileId = "vps-1",
                    remotePath = "/root/projects/TariffiaCode",
                ),
            )
            assertTrue(target.connect().isSuccess)

            // No explicit directory: the mapping's remote path must be used.
            target.createSession(title = null, directory = null)

            assertEquals(listOf("/root/projects/TariffiaCode"), service.createdSessionDirectories)
            target.disconnect()
        }

    @Test
    fun `an explicit directory wins over the mapping`() =
        runTest {
            val ssh = startSshServer()
            val service = RecordingOpenCode().also { openCode = it }
            val target =
                VpsRuntimeTarget(VpsRuntimeConnector(MinaSshPortForwarder(), credentialStore()), remotePort = service.port)

            target.trustServer(ssh.port)
            target.selectRemoteProject(
                RemoteProject(
                    id = "m1",
                    projectRef = "p",
                    sshProfileId = "vps-1",
                    remotePath = "/root/mapped",
                ),
            )
            assertTrue(target.connect().isSuccess)

            target.createSession(title = null, directory = "/explicit/path")

            assertEquals(listOf("/explicit/path"), service.createdSessionDirectories)
            target.disconnect()
        }

    @Test
    fun `a mapping for another ssh profile is ignored`() =
        runTest {
            val ssh = startSshServer()
            val service = RecordingOpenCode().also { openCode = it }
            val target =
                VpsRuntimeTarget(VpsRuntimeConnector(MinaSshPortForwarder(), credentialStore()), remotePort = service.port)

            target.trustServer(ssh.port)
            target.selectRemoteProject(
                RemoteProject(
                    id = "m1",
                    projectRef = "p",
                    sshProfileId = "some-other-vps",
                    remotePath = "/root/mapped",
                ),
            )
            assertTrue(target.connect().isSuccess)

            target.createSession(title = null, directory = null)

            // No mapping matched, so no directory query is sent.
            assertEquals(listOf<String?>(null), service.createdSessionDirectories)
            target.disconnect()
        }

    /** A minimal OpenCode HTTP server that records the directory sent with `POST /session`. */
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
                val contentLength = reader.lineSequenceHeaders()
                // Drain any request body.
                if (contentLength > 0) {
                    val body = CharArray(contentLength)
                    reader.read(body)
                }
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

        private fun directoryOf(requestLine: String): String? {
            val query = requestLine.substringAfter('?', "").substringBefore(' ')
            return query
                .split('&')
                .firstOrNull { it.startsWith("directory=") }
                ?.substringAfter("directory=")
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        }

        override fun close() {
            runCatching { server.close() }
            runCatching { accepting.interrupt() }
        }
    }

    private fun BufferedReader.lineSequenceHeaders(): Int {
        var contentLength = 0
        while (true) {
            val line = readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        return contentLength
    }
}
