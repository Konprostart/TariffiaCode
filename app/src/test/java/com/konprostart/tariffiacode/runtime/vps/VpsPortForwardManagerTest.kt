package com.konprostart.tariffiacode.runtime.vps

import com.konprostart.tariffiacode.core.ssh.SshAuth
import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.core.ssh.SshHostKeyVerifier
import com.konprostart.tariffiacode.core.ssh.SshPortForward
import com.konprostart.tariffiacode.core.ssh.SshPortForwardResult
import com.konprostart.tariffiacode.core.ssh.SshPortForwarder
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshCredential
import com.konprostart.tariffiacode.data.ssh.SshCredentialCodec
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpsPortForwardManagerTest {
    private class FakeForward : SshPortForward {
        override val localHost = "127.0.0.1"
        override val localPort = 45678
        override val remoteHost = "127.0.0.1"
        override val remotePort = 5173
        override var isOpen = true
        var closed = false

        override fun close() {
            closed = true
            isOpen = false
        }
    }

    private class FakeForwarder(
        private val result: SshPortForwardResult,
    ) : SshPortForwarder {
        var requestedRemotePort: Int? = null
        var calls: Int = 0

        override suspend fun openLocalForward(
            host: String,
            port: Int,
            auth: SshAuth,
            verifier: SshHostKeyVerifier,
            remoteHost: String,
            remotePort: Int,
            connectTimeoutMillis: Long,
        ): SshPortForwardResult {
            requestedRemotePort = remotePort
            calls++
            return result
        }
    }

    private fun credentialStore(credential: SshCredential?): SshCredentialStore {
        val entries = if (credential == null) emptyMap() else mapOf("ref" to SshCredentialCodec.encode(credential))
        return SshCredentialStore(load = { entries }, save = {})
    }

    private fun profile(authType: SshAuthType = SshAuthType.PASSWORD) =
        SshProfile(
            id = "vps-1",
            name = "VPS",
            host = "example.com",
            username = "root",
            authType = authType,
            credentialRef = "ref",
        )

    @Test
    fun `listening forward returns the loopback URL to open in the browser`() =
        runTest {
            val forward = FakeForward()
            val forwarder = FakeForwarder(SshPortForwardResult.Listening(forward))
            val manager = VpsPortForwardManager(forwarder, credentialStore(SshCredential.Password("s")))

            val outcome = manager.open(profile(), remotePort = 5173)

            assertTrue(outcome is VpsPortForwardOutcome.Connected)
            assertEquals("http://127.0.0.1:45678/", (outcome as VpsPortForwardOutcome.Connected).localUrl)
            assertEquals(forward, outcome.forward)
            assertEquals(5173, forwarder.requestedRemotePort)
        }

    @Test
    fun `untrusted host key is surfaced and can be trusted then retried`() =
        runTest {
            val key = SshHostKey("ssh-ed25519", "ab".repeat(32))
            val manager =
                VpsPortForwardManager(
                    FakeForwarder(SshPortForwardResult.HostKeyUntrusted(key)),
                    credentialStore(SshCredential.Password("s")),
                )

            val outcome = manager.open(profile(), remotePort = 3000)

            assertTrue(outcome is VpsPortForwardOutcome.NeedsHostKeyTrust)
            assertEquals(key, (outcome as VpsPortForwardOutcome.NeedsHostKeyTrust).hostKey)
        }

    @Test
    fun `missing credential is surfaced without opening a forward`() =
        runTest {
            val forwarder = FakeForwarder(SshPortForwardResult.Listening(FakeForward()))
            val manager = VpsPortForwardManager(forwarder, credentialStore(null))

            val outcome = manager.open(profile(), remotePort = 5173)

            assertEquals(VpsPortForwardOutcome.MissingCredential, outcome)
            assertNull(forwarder.requestedRemotePort)
        }

    @Test
    fun `a credential of the wrong type for the profile is treated as missing`() =
        runTest {
            val forwarder = FakeForwarder(SshPortForwardResult.Listening(FakeForward()))
            // A password profile whose stored secret is a private key cannot build auth.
            val manager = VpsPortForwardManager(forwarder, credentialStore(SshCredential.PrivateKey("-----BEGIN KEY-----")))

            val outcome = manager.open(profile(authType = SshAuthType.PASSWORD), remotePort = 5173)

            assertEquals(VpsPortForwardOutcome.MissingCredential, outcome)
            assertNull(forwarder.requestedRemotePort)
        }

    @Test
    fun `a forward failure is surfaced`() =
        runTest {
            val manager =
                VpsPortForwardManager(
                    FakeForwarder(SshPortForwardResult.Failure("connection refused")),
                    credentialStore(SshCredential.Password("s")),
                )

            val outcome = manager.open(profile(), remotePort = 8080)

            assertTrue(outcome is VpsPortForwardOutcome.Failed)
            assertTrue((outcome as VpsPortForwardOutcome.Failed).message.contains("connection refused"))
        }

    @Test
    fun `closing the returned forward closes the tunnel`() =
        runTest {
            val forward = FakeForward()
            val manager =
                VpsPortForwardManager(
                    FakeForwarder(SshPortForwardResult.Listening(forward)),
                    credentialStore(SshCredential.Password("s")),
                )

            val outcome = manager.open(profile(), remotePort = 5173) as VpsPortForwardOutcome.Connected
            outcome.forward.close()

            assertTrue(forward.closed)
        }
}
