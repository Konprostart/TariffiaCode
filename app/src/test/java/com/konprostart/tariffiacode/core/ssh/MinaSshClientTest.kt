package com.konprostart.tariffiacode.core.ssh

import kotlinx.coroutines.test.runTest
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.server.shell.InteractiveProcessShellFactory
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.util.Base64

/**
 * Foundation tests for [MinaSshClient] using MINA's in-process server. No real VPS is required.
 *
 * Host-key policy is exercised with fake verifiers so password/key/auth logic is tested independently
 * of trust state.
 */
class MinaSshClientTest {
    private var server: SshServer? = null

    @After
    fun tearDown() {
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

    private fun startServer(
        password: String = "secret",
        authorizedKeys: List<PublicKey> = emptyList(),
    ): SshServer {
        val s = SshServer.setUpDefaultServer()
        s.port = 0
        val hostKey = File.createTempFile("hostkey", ".ser").apply { deleteOnExit() }
        s.keyPairProvider = SimpleGeneratorHostKeyProvider(hostKey.toPath())
        s.passwordAuthenticator =
            PasswordAuthenticator { username, pass, _ ->
                username == "tester" && pass == password
            }
        s.publickeyAuthenticator =
            PublickeyAuthenticator { username, key, _: ServerSession ->
                username == "tester" && authorizedKeys.any { it == key }
            }
        // A simple shell factory so `createShellChannel` can open a PTY.
        s.shellFactory = InteractiveProcessShellFactory.INSTANCE
        s.start()
        server = s
        return s
    }

    private fun rsaKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun privateKeyPem(pair: KeyPair): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded)
        return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
    }

    @Test
    fun `password authentication connects and opens a shell`() =
        runTest {
            val s = startServer(password = "secret")
            val client = MinaSshClient()
            val result =
                client.connect(
                    host = "127.0.0.1",
                    port = s.port,
                    auth = SshAuth.Password("tester", "secret"),
                    verifier = trustAllVerifier(),
                )
            assertTrue("expected Connected, got $result", result is SshConnectResult.Connected)
            (result as SshConnectResult.Connected).session.close()
        }

    @Test
    fun `private key authentication connects`() =
        runTest {
            val pair = rsaKeyPair()
            val s = startServer(authorizedKeys = listOf(pair.public))
            val client = MinaSshClient()
            val result =
                client.connect(
                    host = "127.0.0.1",
                    port = s.port,
                    auth = SshAuth.PrivateKey("tester", privateKeyPem(pair)),
                    verifier = trustAllVerifier(),
                )
            assertTrue("expected Connected, got $result", result is SshConnectResult.Connected)
            (result as SshConnectResult.Connected).session.close()
        }

    @Test
    fun `wrong password fails`() =
        runTest {
            val s = startServer(password = "secret")
            val client = MinaSshClient()
            val result =
                client.connect(
                    host = "127.0.0.1",
                    port = s.port,
                    auth = SshAuth.Password("tester", "wrong"),
                    verifier = trustAllVerifier(),
                )
            assertTrue("expected Failure, got $result", result is SshConnectResult.Failure)
        }

    @Test
    fun `untrusted host key is surfaced with its fingerprint`() =
        runTest {
            val s = startServer(password = "secret")
            val client = MinaSshClient()
            val result =
                client.connect(
                    host = "127.0.0.1",
                    port = s.port,
                    auth = SshAuth.Password("tester", "secret"),
                    verifier =
                        object : SshHostKeyVerifier {
                            override fun verify(
                                host: String,
                                port: Int,
                                hostKey: SshHostKey,
                            ) = SshHostKeyDecision.Unknown(hostKey)
                        },
                )
            assertTrue("expected HostKeyUntrusted, got $result", result is SshConnectResult.HostKeyUntrusted)
            val key = (result as SshConnectResult.HostKeyUntrusted).hostKey
            assertTrue("fingerprint must be hex sha256", key.sha256Fingerprint.matches(Regex("^[0-9a-f]{64}$")))
        }

    @Test
    fun `host key mismatch against a pinned fingerprint is rejected`() =
        runTest {
            val s = startServer(password = "secret")
            val client = MinaSshClient()
            // Pin a deliberately wrong fingerprint: verification must reject, not connect.
            var observed: SshHostKey? = null
            val result =
                client.connect(
                    host = "127.0.0.1",
                    port = s.port,
                    auth = SshAuth.Password("tester", "secret"),
                    verifier =
                        object : SshHostKeyVerifier {
                            override fun verify(
                                host: String,
                                port: Int,
                                hostKey: SshHostKey,
                            ): SshHostKeyDecision {
                                observed = hostKey
                                val mismatch = hostKey.sha256Fingerprint != "0".repeat(64)
                                return if (mismatch) SshHostKeyDecision.Unknown(hostKey) else SshHostKeyDecision.Trusted
                            }
                        },
                )
            assertTrue(result is SshConnectResult.HostKeyUntrusted)
            assertTrue(observed != null)
        }

    @Test
    fun `a client initialisation Error is reported as a failure instead of crashing`() =
        runTest {
            // Android/MINA static init can throw an Error (not an Exception) on Connect; it must be
            // surfaced as a normal failure, never escape into the caller's coroutine.
            val client =
                MinaSshClient(
                    clientFactory = { throw NoClassDefFoundError("javax/security/auth/login/CredentialException") },
                )
            val result =
                client.connect(
                    host = "127.0.0.1",
                    port = 22,
                    auth = SshAuth.Password("tester", "secret"),
                    verifier = trustAllVerifier(),
                )
            assertTrue("expected Failure, got $result", result is SshConnectResult.Failure)
            assertTrue((result as SshConnectResult.Failure).message.contains("NoClassDefFoundError"))
        }
}
