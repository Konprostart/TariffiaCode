package com.konprostart.tariffiacode.runtime.vps

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.konprostart.tariffiacode.core.ssh.MinaSshPortForwarder
import com.konprostart.tariffiacode.data.connection.SecureSettingsRepository
import com.konprostart.tariffiacode.data.remote.RemoteProject
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshCredential
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.data.ssh.SshProfileStore
import com.konprostart.tariffiacode.runtime.RuntimeState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Runs the actual Remote Project SSH tunnel path on Android ART without a VPS or repository secrets. */
@RunWith(AndroidJUnit4::class)
class VpsSshConnectInstrumentedTest {
    @Test
    fun persistedPasswordAuthenticatesAndForwardsToLoopbackOpenCodeHealth() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val settings = SecureSettingsRepository(context)
            val profiles = SshProfileStore(settings)
            val credentials = SshCredentialStore(settings)
            val profileId = "instrumented-ssh-${UUID.randomUUID()}"
            val credentialRef = "instrumented-credential-${UUID.randomUUID()}"
            val password = "runtime-test-${UUID.randomUUID()}"
            val acceptedPassword = AtomicBoolean(false)
            val hostKeyFile = File(context.cacheDir, "$profileId-hostkey.ser")
            val sshServer = SshServer.setUpDefaultServer()
            val healthServer = LoopbackOpenCodeHealthServer("art-test")
            var target: VpsRuntimeTarget? = null

            try {
                sshServer.port = 0
                sshServer.keyPairProvider = SimpleGeneratorHostKeyProvider(hostKeyFile.toPath())
                sshServer.passwordAuthenticator =
                    PasswordAuthenticator { username, candidate, _ ->
                        val accepted = username == "ssh-test-user" && candidate == password
                        if (accepted) acceptedPassword.set(true)
                        accepted
                    }
                sshServer.forwardingFilter = AcceptAllForwardingFilter.INSTANCE
                sshServer.start()
                healthServer.start()

                val profile =
                    SshProfile(
                        id = profileId,
                        name = "Instrumented VPS",
                        host = "127.0.0.1",
                        port = sshServer.port,
                        username = "ssh-test-user",
                        authType = SshAuthType.PASSWORD,
                        credentialRef = credentialRef,
                    )
                credentials.setCredential(credentialRef, SshCredential.Password(password))
                profiles.upsert(profile)

                // Recreate the encrypted repository/store before connecting, as happens after app restart.
                val reopenedSettings = SecureSettingsRepository(context)
                val reopenedProfiles = SshProfileStore(reopenedSettings)
                val reopenedCredentials = SshCredentialStore(reopenedSettings)
                val reopenedProfile = reopenedProfiles.profile(profileId) ?: error("saved SSH profile did not reload")
                assertEquals(profile, reopenedProfile)
                val storedCredential = reopenedCredentials.credential(credentialRef) as? SshCredential.Password
                assertTrue("password must persist in encrypted settings", storedCredential?.password == password)

                val activeTarget =
                    VpsRuntimeTarget(
                        connector = VpsRuntimeConnector(MinaSshPortForwarder(), reopenedCredentials),
                        remotePort = healthServer.port,
                        profiles = reopenedProfiles,
                    )
                target = activeTarget
                activeTarget.selectProfile(reopenedProfile)
                activeTarget.selectRemoteProject(
                    RemoteProject(
                        id = "instrumented-project-${UUID.randomUUID()}",
                        projectRef = "test/ssh-connect",
                        sshProfileId = profileId,
                        remotePath = "/tmp/ssh-connect-test",
                    ),
                )

                // First handshake is intentionally rejected until the user trusts the ephemeral host key.
                val untrusted = withTimeout(30_000L) { activeTarget.connect() }
                assertTrue("first connection must wait for explicit host-key trust", untrusted.isFailure)
                val presentedHostKey = activeTarget.pendingHostKey.value
                assertNotNull("MINA must surface the ephemeral server host key", presentedHostKey)
                val trustedProfile = activeTarget.trustHostKey(reopenedProfile, presentedHostKey!!)
                reopenedProfiles.upsert(trustedProfile)
                activeTarget.selectProfile(trustedProfile)

                val connected = withTimeout(30_000L) { activeTarget.connect() }

                assertTrue("saved password should authenticate and open the SSH forward", connected.isSuccess)
                assertTrue("test SSH server must accept the persisted password", acceptedPassword.get())
                assertEquals("art-test", connected.getOrThrow().version)
                assertTrue(activeTarget.state.value is RuntimeState.Connected)
                assertTrue(activeTarget.isForwardOpen)
            } finally {
                target?.disconnect()
                profiles.delete(profileId)
                credentials.clearCredential(credentialRef)
                runCatching { healthServer.close() }
                runCatching { sshServer.stop(true) }
                runCatching { hostKeyFile.delete() }
            }
        }

    /** Minimal HTTP health endpoint reached only through the SSH local forward. */
    private class LoopbackOpenCodeHealthServer(private val version: String) : AutoCloseable {
        private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        private val acceptThread =
            Thread {
                while (!server.isClosed) {
                    val client =
                        try {
                            server.accept()
                        } catch (_: Exception) {
                            return@Thread
                        }
                    Thread { respond(client) }.apply {
                        isDaemon = true
                        start()
                    }
                }
            }.apply {
                isDaemon = true
            }

        val port: Int get() = server.localPort

        fun start() = acceptThread.start()

        private fun respond(client: Socket) {
            client.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val body = """{"healthy":true,"version":"$version"}""".toByteArray()
                val status = if (requestLine.contains("/global/health")) "200 OK" else "404 Not Found"
                val response =
                    "HTTP/1.1 $status\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${if (status.startsWith("200")) body.size else 0}\r\n" +
                        "Connection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(response.toByteArray())
                    if (status.startsWith("200")) write(body)
                    flush()
                }
            }
        }

        override fun close() {
            runCatching { server.close() }
            runCatching { acceptThread.interrupt() }
        }
    }
}
