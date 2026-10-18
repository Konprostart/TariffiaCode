package com.konprostart.tariffiacode.feature.ssh

import com.konprostart.tariffiacode.core.ssh.SshAuth
import com.konprostart.tariffiacode.core.ssh.SshConnectResult
import com.konprostart.tariffiacode.core.ssh.SshConnectionClient
import com.konprostart.tariffiacode.core.ssh.SshHostKeyVerifier
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshCredential
import com.konprostart.tariffiacode.data.ssh.SshCredentialCodec
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshHeldSessionStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.data.ssh.SshProfileStore
import com.konprostart.tariffiacode.runtime.ssh.SshConnectionManager
import com.konprostart.tariffiacode.runtime.ssh.SshSessionHolder
import kotlinx.coroutines.CoroutineScope
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

    /**
     * Same wiring the app builds: holder owns the session; the VM only mirrors it, so every
     * assertion here now exercises the exact code path a press of Connect takes in production.
     */
    private fun screenViewModel(
        profile: SshProfile,
        entries: Map<String, String>,
        client: SshConnectionClient,
        testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ): SshSettingsViewModel {
        val credentialStore = SshCredentialStore(load = { entries }, save = {})
        val profileStore =
            SshProfileStore(
                load = { listOf(profile) },
                save = {},
                credentials = credentialStore,
            )
        val holder =
            SshSessionHolder(
                opener =
                    SshConnectionManager(client = client, credentials = credentialStore).diagnosticOpener(
                        credentialStore,
                    ),
                profiles = profileStore,
                selection = SshHeldSessionStore({ null }, {}),
                scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            )
        return SshSettingsViewModel(
            profiles = profileStore,
            credentials = credentialStore,
            sessions = holder,
        )
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
            val vm = screenViewModel(profile, entries, ThrowingClient(), testScheduler)

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
            val entries = mapOf("ref" to SshCredentialCodec.encode(SshCredential.Password(password)))
            val failure =
                SshConnectResult.Failure(
                    message = "auth() failed for $password",
                    cause =
                        IllegalStateException(
                            "MINA authentication failed",
                            java.security.NoSuchAlgorithmException("provider BC cannot load $password"),
                        ),
                )
            val vm = screenViewModel(profile, entries, FailingClient(failure), testScheduler)

            vm.connect(profile.id)

            val message = (vm.state.value.connection as? SshConnectionStatus.Error)?.message.orEmpty()
            assertTrue(message.contains("auth() failed"))
            assertTrue(message.contains("java.security.NoSuchAlgorithmException"))
            assertTrue(message.contains("provider BC cannot load ***"))
            assertFalse(message.contains(password))
        }
}
