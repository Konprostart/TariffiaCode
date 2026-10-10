package com.konprostart.tariffiacode.feature.ssh

import com.konprostart.tariffiacode.core.ssh.SshAuth
import com.konprostart.tariffiacode.core.ssh.SshConnectResult
import com.konprostart.tariffiacode.core.ssh.SshConnectionClient
import com.konprostart.tariffiacode.core.ssh.SshHostKeyVerifier
import com.konprostart.tariffiacode.core.ssh.SshPortForwardResult
import com.konprostart.tariffiacode.core.ssh.SshPortForwarder
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshCredential
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.data.ssh.SshProfileStore
import com.konprostart.tariffiacode.runtime.RuntimeState
import com.konprostart.tariffiacode.runtime.vps.VpsRuntimeConnector
import com.konprostart.tariffiacode.runtime.vps.VpsRuntimeTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshSettingsViewModelTest {
    private class Backend {
        var profiles: List<SshProfile> = emptyList()
        var credentials: Map<String, String> = emptyMap()
    }

    private fun viewModel(backend: Backend): SshSettingsViewModel {
        val credentialStore = SshCredentialStore(load = { backend.credentials }, save = { backend.credentials = it })
        val profileStore =
            SshProfileStore(
                load = { backend.profiles },
                save = { backend.profiles = it },
                credentials = credentialStore,
            )
        return SshSettingsViewModel(
            profiles = profileStore,
            credentials = credentialStore,
            connections =
                SshConnectionManager(
                    client = NoopClient,
                    credentials = credentialStore,
                ),
        )
    }

    private object NoopClient : SshConnectionClient {
        override suspend fun connect(
            host: String,
            port: Int,
            auth: SshAuth,
            verifier: SshHostKeyVerifier,
            connectTimeoutMillis: Long,
        ): SshConnectResult = SshConnectResult.Failure("unused in this test")
    }

    private class RecordingForwarder(
        private val failure: SshPortForwardResult.Failure = SshPortForwardResult.Failure("test forwarder reached"),
    ) : SshPortForwarder {
        var capturedAuth: SshAuth? = null

        override suspend fun openLocalForward(
            host: String,
            port: Int,
            auth: SshAuth,
            verifier: SshHostKeyVerifier,
            remoteHost: String,
            remotePort: Int,
            connectTimeoutMillis: Long,
        ): SshPortForwardResult {
            capturedAuth = auth
            return failure
        }
    }

    @Test
    fun `saving a password profile stores metadata and secret separately`() {
        val backend = Backend()
        val vm = viewModel(backend)

        vm.newProfile()
        vm.updateForm {
            it.copy(
                name = "VPS",
                host = "example.com",
                port = "22",
                username = "root",
                authType = SshAuthType.PASSWORD,
                password = "hunter2",
            )
        }
        vm.saveProfile()

        assertEquals(1, backend.profiles.size)
        val profile = backend.profiles.single()
        // Metadata never carries the secret.
        assertFalse(profile.toString().contains("hunter2"))
        // The secret is stored under the profile's reference.
        val stored = SshCredentialStore(load = { backend.credentials }, save = {}).credential(profile.credentialRef)
        assertEquals(SshCredential.Password("hunter2"), stored)
    }

    @Test
    fun `saving a private key profile stores the key`() {
        val backend = Backend()
        val vm = viewModel(backend)

        vm.newProfile()
        vm.updateForm {
            it.copy(
                name = "VPS",
                host = "example.com",
                port = "2200",
                username = "root",
                authType = SshAuthType.PRIVATE_KEY,
                privateKeyPem = "-----BEGIN KEY-----",
                passphrase = "phrase",
            )
        }
        vm.saveProfile()

        val profile = backend.profiles.single()
        assertEquals(SshAuthType.PRIVATE_KEY, profile.authType)
        assertEquals(2200, profile.port)
        val stored = SshCredentialStore(load = { backend.credentials }, save = {}).credential(profile.credentialRef)
        assertEquals(SshCredential.PrivateKey("-----BEGIN KEY-----", "phrase"), stored)
    }

    @Test
    fun `missing required fields block saving`() {
        val backend = Backend()
        val vm = viewModel(backend)

        vm.newProfile()
        vm.updateForm { it.copy(name = "VPS", host = "", username = "", password = "x") }
        vm.saveProfile()

        assertTrue(backend.profiles.isEmpty())
        val errors = vm.state.value.form?.errors.orEmpty()
        assertTrue(errors.containsKey(SshProfileValidator.FIELD_HOST))
        assertTrue(errors.containsKey(SshProfileValidator.FIELD_USERNAME))
    }

    @Test
    fun `missing credential blocks saving`() {
        val backend = Backend()
        val vm = viewModel(backend)

        vm.newProfile()
        vm.updateForm {
            it.copy(name = "VPS", host = "example.com", port = "22", username = "root", authType = SshAuthType.PASSWORD)
        }
        vm.saveProfile()

        assertTrue(backend.profiles.isEmpty())
        assertTrue(vm.state.value.form?.errors?.containsKey(SshProfileValidator.FIELD_CREDENTIAL) == true)
    }

    @Test
    fun `editing without retyping the credential keeps the stored secret`() {
        val backend = Backend()
        val vm = viewModel(backend)
        vm.newProfile()
        vm.updateForm {
            it.copy(name = "VPS", host = "example.com", port = "22", username = "root", password = "hunter2")
        }
        vm.saveProfile()
        val created = backend.profiles.single()

        vm.editProfile(created.id)
        vm.updateForm { it.copy(name = "Renamed") }
        vm.saveProfile()

        val updated = backend.profiles.single()
        assertEquals("Renamed", updated.name)
        val stored = SshCredentialStore(load = { backend.credentials }, save = {}).credential(created.credentialRef)
        assertEquals(SshCredential.Password("hunter2"), stored)
    }

    @Test
    fun `saved password survives viewmodel recreation and reaches ssh authentication`() =
        runTest {
            val password = "test-password"
            val backend = Backend()
            val vm = viewModel(backend)
            vm.newProfile()
            vm.updateForm {
                it.copy(
                    name = "VPS",
                    host = "example.com",
                    port = "22",
                    username = "root",
                    authType = SshAuthType.PASSWORD,
                    password = password,
                )
            }
            vm.saveProfile()
            val savedProfile = backend.profiles.single()

            val reopenedVm = viewModel(backend)
            reopenedVm.editProfile(savedProfile.id)
            assertTrue(reopenedVm.state.value.form?.credentialAlreadyStored == true)

            val reloadedCredentials = SshCredentialStore(load = { backend.credentials }, save = {})
            val forwarder =
                RecordingForwarder(
                    SshPortForwardResult.Failure(
                        message = "auth() failed for $password",
                        cause =
                            IllegalStateException(
                                "MINA authentication failed",
                                java.security.NoSuchAlgorithmException("provider BC cannot load $password"),
                            ),
                    ),
                )
            val target =
                VpsRuntimeTarget(
                    connector = VpsRuntimeConnector(forwarder, reloadedCredentials),
                    profiles =
                        SshProfileStore(
                            load = { backend.profiles },
                            save = {},
                            credentials = reloadedCredentials,
                        ),
                )
            target.selectProfile(savedProfile)
            target.connect()

            val errorMessage = (target.state.value as? RuntimeState.Failed)?.message.orEmpty()
            assertTrue(errorMessage.contains("auth() failed"))
            assertTrue(errorMessage.contains("java.security.NoSuchAlgorithmException"))
            assertTrue(errorMessage.contains("provider BC cannot load ***"))
            assertFalse(errorMessage.contains(password))
            val auth = forwarder.capturedAuth as? SshAuth.Password
            assertTrue("password auth must reach the Remote Project SSH forward", auth != null)
            assertTrue("stored password must be unchanged", auth?.password == password)
        }

    @Test
    fun `changing auth type cannot silently reuse a saved password as a private key`() {
        val backend = Backend()
        val vm = viewModel(backend)
        vm.newProfile()
        vm.updateForm {
            it.copy(name = "VPS", host = "example.com", username = "root", password = "test-password")
        }
        vm.saveProfile()
        val savedProfile = backend.profiles.single()

        vm.editProfile(savedProfile.id)
        vm.updateForm { it.copy(authType = SshAuthType.PRIVATE_KEY) }
        assertFalse(vm.state.value.form?.credentialAlreadyStored == true)
        assertTrue(vm.state.value.form?.isCredentialMissing() == true)

        vm.saveProfile()

        assertEquals(SshAuthType.PASSWORD, backend.profiles.single().authType)
        assertTrue(vm.state.value.form?.errors?.containsKey(SshProfileValidator.FIELD_CREDENTIAL) == true)
        val stored = SshCredentialStore(load = { backend.credentials }, save = {}).credential(savedProfile.credentialRef)
        assertEquals(SshCredential.Password("test-password"), stored)
    }

    @Test
    fun `deleting a profile removes its stored secret`() {
        val backend = Backend()
        val vm = viewModel(backend)
        vm.newProfile()
        vm.updateForm {
            it.copy(name = "VPS", host = "example.com", port = "22", username = "root", password = "hunter2")
        }
        vm.saveProfile()
        val created = backend.profiles.single()

        vm.deleteProfile(created.id)

        assertTrue(backend.profiles.isEmpty())
        assertTrue(backend.credentials.isEmpty())
    }
}
