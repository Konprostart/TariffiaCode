package com.konprostart.tariffiacode.feature.ssh

import com.konprostart.tariffiacode.core.ssh.SshAuth
import com.konprostart.tariffiacode.core.ssh.SshConnectResult
import com.konprostart.tariffiacode.core.ssh.SshConnectionClient
import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.core.ssh.SshHostKeyDecision
import com.konprostart.tariffiacode.core.ssh.SshHostKeyVerifier
import com.konprostart.tariffiacode.core.ssh.SshSession
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshCredential
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshConnectionManagerTest {
    private val hostKey = SshHostKey(keyType = "ssh-ed25519", sha256Fingerprint = "ab".repeat(32))

    private class FakeSession : SshSession {
        override fun writeStandardInput(bytes: ByteArray) = Unit

        override fun resize(
            columns: Int,
            rows: Int,
        ) = Unit

        override val output: Flow<ByteArray> = emptyFlow()
        override val isOpen: Boolean = true

        override fun close() = Unit
    }

    /** Transport fake: consults the verifier and mirrors the real transport's Result mapping. */
    private class FakeClient(
        private val hostKey: SshHostKey,
        var capturedAuth: SshAuth? = null,
    ) : SshConnectionClient {
        override suspend fun connect(
            host: String,
            port: Int,
            auth: SshAuth,
            verifier: SshHostKeyVerifier,
            connectTimeoutMillis: Long,
        ): SshConnectResult {
            capturedAuth = auth
            return when (verifier.verify(host, port, hostKey)) {
                is SshHostKeyDecision.Trusted -> SshConnectResult.Connected(FakeSession())
                is SshHostKeyDecision.Unknown -> SshConnectResult.HostKeyUntrusted(hostKey)
            }
        }
    }

    private fun credentialStore(credential: SshCredential?): SshCredentialStore {
        val map =
            if (credential == null) {
                emptyMap()
            } else {
                mapOf(
                    "ref" to com.konprostart.tariffiacode.data.ssh.SshCredentialCodec.encode(credential),
                )
            }
        return SshCredentialStore(load = { map }, save = {})
    }

    private fun profile(
        authType: SshAuthType = SshAuthType.PASSWORD,
        trusted: String? = null,
    ) = SshProfile(
        id = "p",
        name = "P",
        host = "example.com",
        port = 22,
        username = "root",
        authType = authType,
        credentialRef = "ref",
        trustedHostKeyFingerprint = trusted,
    )

    @Test
    fun `password credential connects when host key is trusted`() =
        runTest {
            val client = FakeClient(hostKey)
            val manager = SshConnectionManager(client, credentialStore(SshCredential.Password("secret")))
            val outcome = manager.connect(profile(trusted = hostKey.sha256Fingerprint))

            assertTrue(outcome is SshConnectionOutcome.Connected)
            assertEquals(SshAuth.Password("root", "secret"), client.capturedAuth)
        }

    @Test
    fun `private key credential is turned into key auth`() =
        runTest {
            val client = FakeClient(hostKey)
            val manager =
                SshConnectionManager(
                    client,
                    credentialStore(SshCredential.PrivateKey("PEM", "phrase")),
                )
            manager.connect(profile(authType = SshAuthType.PRIVATE_KEY, trusted = hostKey.sha256Fingerprint))

            assertEquals(SshAuth.PrivateKey("root", "PEM", "phrase"), client.capturedAuth)
        }

    @Test
    fun `unknown host key is surfaced for confirmation`() =
        runTest {
            val manager = SshConnectionManager(FakeClient(hostKey), credentialStore(SshCredential.Password("secret")))
            val outcome = manager.connect(profile(trusted = null))

            assertTrue(outcome is SshConnectionOutcome.NeedsHostKeyTrust)
            assertEquals(hostKey, (outcome as SshConnectionOutcome.NeedsHostKeyTrust).hostKey)
        }

    @Test
    fun `trusted fingerprint mismatch is rejected never offered to trust`() =
        runTest {
            val manager =
                SshConnectionManager(
                    FakeClient(hostKey),
                    credentialStore(SshCredential.Password("secret")),
                )
            val other = "cd".repeat(32)
            val outcome = manager.connect(profile(trusted = other))

            assertTrue(outcome is SshConnectionOutcome.HostKeyMismatch)
            val mismatch = outcome as SshConnectionOutcome.HostKeyMismatch
            assertEquals(other, mismatch.expectedFingerprint)
            assertEquals(hostKey.sha256Fingerprint, mismatch.presentedFingerprint)
        }

    @Test
    fun `missing credential is reported without calling the transport`() =
        runTest {
            val client = FakeClient(hostKey)
            val manager = SshConnectionManager(client, credentialStore(null))
            val outcome = manager.connect(profile(trusted = hostKey.sha256Fingerprint))

            assertEquals(SshConnectionOutcome.MissingCredential, outcome)
            assertEquals(null, client.capturedAuth)
        }

    @Test
    fun `auth type mismatch between profile and stored credential yields missing credential`() =
        runTest {
            val manager =
                SshConnectionManager(
                    FakeClient(hostKey),
                    credentialStore(SshCredential.PrivateKey("PEM")),
                )
            // Profile says password, store holds a key.
            val outcome = manager.connect(profile(authType = SshAuthType.PASSWORD, trusted = hostKey.sha256Fingerprint))
            assertEquals(SshConnectionOutcome.MissingCredential, outcome)
        }
}
