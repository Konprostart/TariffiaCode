package com.konprostart.tariffiacode.feature.ssh

import com.konprostart.tariffiacode.core.ssh.SshAuth
import com.konprostart.tariffiacode.core.ssh.SshConnectResult
import com.konprostart.tariffiacode.core.ssh.SshConnectionClient
import com.konprostart.tariffiacode.core.ssh.SshHostKeyVerifier
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshCredential
import com.konprostart.tariffiacode.data.ssh.SshCredentialCodec
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.data.ssh.SshProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Regression: a transport that throws (including an Error) must not crash the app on Connect. */
class SshSettingsViewModelConnectTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class ThrowingClient : SshConnectionClient {
        override suspend fun connect(
            host: String,
            port: Int,
            auth: SshAuth,
            verifier: SshHostKeyVerifier,
            connectTimeoutMillis: Long,
        ): SshConnectResult = throw NoClassDefFoundError("javax/security/auth/login/CredentialException")
    }

    private class FailingClient(
        private val failure: SshConnectResult.Failure,
    ) : SshConnectionClient {
        override suspend fun connect(
            host: String,
            port: Int,
            auth: SshAuth,
            verifier: SshHostKeyVerifier,
            connectTimeoutMillis: Long,
        ): SshConnectResult = failure
    }

    @Test
    fun `connect surfaces a thrown Error as an error state instead of crashing`() =
        runTest {
            val profile =
                SshProfile(
                    id = "p1",
                    name = "VPS",
                    host = "example.com",
                    username = "root",
                    authType = SshAuthType.PASSWORD,
                    credentialRef = "ref",
                )
            val entries = mapOf("ref" to SshCredentialCodec.encode(SshCredential.Password("secret")))
            val credentialStore = SshCredentialStore(load = { entries }, save = {})
            val profileStore =
                SshProfileStore(
                    load = { listOf(profile) },
                    save = {},
                    credentials = credentialStore,
                )
            val vm =
                SshSettingsViewModel(
                    profiles = profileStore,
                    credentials = credentialStore,
                    connections = SshConnectionManager(client = ThrowingClient(), credentials = credentialStore),
                )

            vm.connect("p1")

            assertTrue(
                "expected an error state, got ${vm.state.value.connection}",
                vm.state.value.connection is SshConnectionStatus.Error,
            )
            val message = (vm.state.value.connection as SshConnectionStatus.Error).message
            assertTrue(message.contains("NoClassDefFoundError"))
            assertTrue(message.contains("javax/security/auth/login/CredentialException"))
            assertFalse(message.contains("secret"))
        }

    @Test
    fun `connect displays nested SSH causes without exposing the stored password`() =
        runTest {
            val password = "ssh-connect-secret"
            val profile =
                SshProfile(
                    id = "p1",
                    name = "VPS",
                    host = "example.com",
                    username = "root",
                    authType = SshAuthType.PASSWORD,
                    credentialRef = "ref",
                )
            val credentials =
                SshCredentialStore(
                    load = { mapOf("ref" to SshCredentialCodec.encode(SshCredential.Password(password))) },
                    save = {},
                )
            val profileStore = SshProfileStore(load = { listOf(profile) }, save = {}, credentials = credentials)
            val failure =
                SshConnectResult.Failure(
                    message = "auth() failed for $password",
                    cause =
                        IllegalStateException(
                            "MINA authentication failed",
                            java.security.NoSuchAlgorithmException("provider BC cannot load $password"),
                        ),
                )
            val vm =
                SshSettingsViewModel(
                    profiles = profileStore,
                    credentials = credentials,
                    connections = SshConnectionManager(client = FailingClient(failure), credentials = credentials),
                )

            vm.connect(profile.id)

            val message = (vm.state.value.connection as? SshConnectionStatus.Error)?.message.orEmpty()
            assertTrue(message.contains("auth() failed"))
            assertTrue(message.contains("java.security.NoSuchAlgorithmException"))
            assertTrue(message.contains("provider BC cannot load ***"))
            assertFalse(message.contains(password))
        }
}
