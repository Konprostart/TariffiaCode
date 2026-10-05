package com.konprostart.tariffiacode.core.ssh

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.AbstractCommandSupport
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests for the additive [MinaSshShellClient]: it must open a shell that stays usable for input/output and
 * release the SSH client when the session is closed. Uses an in-process MINA server with an echo shell.
 */
class MinaSshShellClientTest {
    private var server: SshServer? = null

    @After
    fun tearDown() {
        runCatching { server?.stop(true) }
    }

    private fun startServer(password: String = "secret"): SshServer {
        val s = SshServer.setUpDefaultServer()
        s.port = 0
        val hostKey = File.createTempFile("hostkey", ".ser").apply { deleteOnExit() }
        s.keyPairProvider = SimpleGeneratorHostKeyProvider(hostKey.toPath())
        s.passwordAuthenticator = PasswordAuthenticator { username, pass, _ -> username == "tester" && pass == password }
        s.shellFactory = EchoShellFactory()
        s.start()
        server = s
        return s
    }

    private fun trustAll() =
        object : SshHostKeyVerifier {
            override fun verify(
                host: String,
                port: Int,
                hostKey: SshHostKey,
            ) = SshHostKeyDecision.Trusted
        }

    @Test
    fun `opens a shell that echoes input back`() =
        runBlocking {
            val s = startServer()
            val result =
                MinaSshShellClient().openShell(
                    host = "127.0.0.1",
                    port = s.port,
                    auth = SshAuth.Password("tester", "secret"),
                    verifier = trustAll(),
                )
            assertTrue("expected Opened, got $result", result is SshShellResult.Opened)
            val session = (result as SshShellResult.Opened).session
            try {
                assertTrue(session.isOpen)
                session.writeStandardInput("hello vps\n".toByteArray())
                val echoed = withTimeout(10_000) { session.output.first { it.decodeToString().contains("hello vps") } }
                assertTrue(echoed.decodeToString().contains("hello vps"))
            } finally {
                session.close()
            }
        }

    @Test
    fun `closing the session is idempotent and reports not open`() =
        runBlocking {
            val s = startServer()
            val opened =
                MinaSshShellClient().openShell("127.0.0.1", s.port, SshAuth.Password("tester", "secret"), trustAll())
            val session = (opened as SshShellResult.Opened).session
            assertTrue(session.isOpen)

            session.close()
            assertFalse(session.isOpen)
            // Repeated close must not throw or leak.
            session.close()
            assertFalse(session.isOpen)
        }

    @Test
    fun `reconnect opens a fresh shell after a close`() =
        runBlocking {
            val s = startServer()
            val client = MinaSshShellClient()
            val opened =
                client.openShell("127.0.0.1", s.port, SshAuth.Password("tester", "secret"), trustAll())
            (opened as SshShellResult.Opened).session.close()

            val second = client.openShell("127.0.0.1", s.port, SshAuth.Password("tester", "secret"), trustAll())
            assertTrue("expected a fresh shell, got $second", second is SshShellResult.Opened)
            (second as SshShellResult.Opened).session.close()
        }

    @Test
    fun `a wrong password fails without leaking`() =
        runBlocking {
            val s = startServer(password = "secret")
            val result =
                MinaSshShellClient().openShell("127.0.0.1", s.port, SshAuth.Password("tester", "wrong"), trustAll())
            assertTrue("expected Failure, got $result", result is SshShellResult.Failure)
        }

    @Test
    fun `an untrusted host key is surfaced and no shell opens`() =
        runBlocking {
            val s = startServer()
            val result =
                MinaSshShellClient().openShell(
                    "127.0.0.1",
                    s.port,
                    SshAuth.Password("tester", "secret"),
                    object : SshHostKeyVerifier {
                        override fun verify(
                            host: String,
                            port: Int,
                            hostKey: SshHostKey,
                        ) = SshHostKeyDecision.Unknown(hostKey)
                    },
                )
            assertTrue("expected HostKeyUntrusted, got $result", result is SshShellResult.HostKeyUntrusted)
        }

    /** A shell that echoes every byte it receives back to the client. */
    private class EchoShellFactory : ShellFactory {
        override fun createShell(channel: ChannelSession) = EchoCommand()
    }

    private class EchoCommand : AbstractCommandSupport("echo", null) {
        override fun run() {
            val input = getInputStream()
            val output = getOutputStream()
            val buffer = ByteArray(1024)
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            } catch (_: Exception) {
                // The channel closed; fall through to exit.
            } finally {
                onExit(0)
            }
        }
    }
}
