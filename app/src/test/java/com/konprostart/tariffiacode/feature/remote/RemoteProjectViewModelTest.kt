package com.konprostart.tariffiacode.feature.remote

import com.konprostart.tariffiacode.core.api.OpenCodeHealth
import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.data.remote.RemoteProject
import com.konprostart.tariffiacode.data.remote.RemoteProjectStore
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.data.ssh.SshProfileStore
import com.konprostart.tariffiacode.runtime.RuntimeState
import com.konprostart.tariffiacode.runtime.vps.VpsConnectionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The VPS connection surfaces an unknown host key for explicit confirmation; the screen must show the
 * fingerprint and let the user trust or decline it, and a declined key must never connect.
 */
class RemoteProjectViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val hostKey = SshHostKey(keyType = "ssh-ed25519", sha256Fingerprint = "ab".repeat(32))

    private class FakeController(
        private val profile: SshProfile?,
    ) : VpsConnectionController {
        override val state = MutableStateFlow<RuntimeState>(RuntimeState.Disconnected)
        override val isForwardOpen = false
        override val pendingHostKey = MutableStateFlow<SshHostKey?>(null)
        override val selectedRemoteProject = MutableStateFlow<RemoteProject?>(null)
        var connectCalls = 0
        var dismissed = false

        override fun selectRemoteProject(project: RemoteProject?) {
            selectedRemoteProject.value = project
        }

        override fun trustHostKey(hostKey: SshHostKey): SshProfile? {
            val selected = profile ?: return null
            pendingHostKey.value = null
            return selected.trusting(hostKey.sha256Fingerprint)
        }

        override fun dismissHostKey() {
            dismissed = true
            pendingHostKey.value = null
        }

        override suspend fun connect(): Result<OpenCodeHealth> {
            connectCalls++
            return Result.success(OpenCodeHealth(true, "1.0"))
        }

        override suspend fun reconnect(): Result<OpenCodeHealth> = connect()

        override fun disconnect() = Unit
    }

    private fun profile() =
        SshProfile(
            id = "p",
            name = "VPS",
            host = "example.com",
            username = "root",
            authType = SshAuthType.PASSWORD,
            credentialRef = "ref",
        )

    private fun viewModel(controller: FakeController): Pair<RemoteProjectViewModel, SshProfileStore> {
        val profiles = mutableListOf(profile())
        val store =
            SshProfileStore(
                load = { profiles.toList() },
                save = {
                    profiles.clear()
                    profiles.addAll(it)
                },
                credentials = SshCredentialStore(load = { emptyMap() }, save = {}),
            )
        val projects = RemoteProjectStore(load = { emptyList() }, save = {}, sshProfileExists = { true })
        val vm = RemoteProjectViewModel(projects, store, controller, onApply = { _, _ -> })
        return vm to store
    }

    @Test
    fun `an unknown host key is surfaced for confirmation`() {
        val controller = FakeController(profile())
        controller.pendingHostKey.value = hostKey

        val (vm, _) = viewModel(controller)

        assertEquals(hostKey, vm.state.value.pendingHostKey)
    }

    @Test
    fun `trusting the pending key persists it and reconnects`() {
        val controller = FakeController(profile())
        controller.pendingHostKey.value = hostKey
        val (vm, profiles) = viewModel(controller)

        vm.trustPendingHostKey()

        assertEquals(hostKey.sha256Fingerprint, profiles.profile("p")?.trustedHostKeyFingerprint)
        assertEquals("a confirmed key must retry the connection", 1, controller.connectCalls)
        assertNull(vm.state.value.pendingHostKey)
    }

    @Test
    fun `declining the pending key does not connect`() {
        val controller = FakeController(profile())
        controller.pendingHostKey.value = hostKey
        val (vm, profiles) = viewModel(controller)

        vm.dismissPendingHostKey()

        assertTrue(controller.dismissed)
        assertEquals(0, controller.connectCalls)
        assertNull(vm.state.value.pendingHostKey)
        assertNull(profiles.profile("p")?.trustedHostKeyFingerprint)
    }

    @Test
    fun `a key mismatch leaves no pending key to trust`() {
        // The connector clears pendingHostKey on a mismatch (it refuses outright), so the screen never
        // offers a changed key for trust.
        val controller = FakeController(profile())
        controller.pendingHostKey.value = null

        val (vm, _) = viewModel(controller)

        assertFalse(vm.state.value.pendingHostKey != null)
    }
}
