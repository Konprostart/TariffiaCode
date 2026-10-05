package com.konprostart.tariffiacode.feature.git

import com.konprostart.tariffiacode.core.api.OpenCodeAgent
import com.konprostart.tariffiacode.core.api.OpenCodeEvent
import com.konprostart.tariffiacode.core.api.OpenCodeFileChange
import com.konprostart.tariffiacode.core.api.OpenCodeHealth
import com.konprostart.tariffiacode.core.api.OpenCodeMessage
import com.konprostart.tariffiacode.core.api.OpenCodeSession
import com.konprostart.tariffiacode.core.api.OpenCodeVcsInfo
import com.konprostart.tariffiacode.core.api.PromptRequest
import com.konprostart.tariffiacode.core.api.ProviderCatalog
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.runtime.BackendKind
import com.konprostart.tariffiacode.runtime.OpenCodeBackend
import com.konprostart.tariffiacode.runtime.PermissionResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteGitViewModelPushTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class NoopBackend : OpenCodeBackend {
        override val id = "vps"
        override val displayName = "VPS"
        override val kind = BackendKind.REMOTE

        override suspend fun health() = OpenCodeHealth(true, "1")

        override suspend fun listSessions(directory: String?): List<OpenCodeSession> = emptyList()

        override suspend fun createSession(
            title: String?,
            directory: String?,
        ): OpenCodeSession = throw UnsupportedOperationException()

        override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = emptyList()

        override suspend fun listProviders(): ProviderCatalog = ProviderCatalog()

        override suspend fun listAgents(): List<OpenCodeAgent> = emptyList()

        override suspend fun sendMessage(
            sessionId: String,
            request: PromptRequest,
        ) = Unit

        override suspend fun abortSession(sessionId: String): Boolean = false

        override suspend fun respondToPermission(
            sessionId: String,
            permissionId: String,
            response: PermissionResponse,
            remember: Boolean,
        ): Boolean = false

        override fun events(): Flow<OpenCodeEvent> = emptyFlow()

        override suspend fun vcsInfo(directory: String): OpenCodeVcsInfo = OpenCodeVcsInfo(branch = "main")

        override suspend fun vcsStatus(directory: String): List<OpenCodeFileChange> = emptyList()

        override suspend fun vcsDiff(
            directory: String,
            mode: String,
            context: Int?,
        ): List<OpenCodeFileChange> = emptyList()
    }

    private class FakeExecutor(
        var outcome: RemoteCommandOutcome,
    ) : RemoteGitCommandExecutor {
        override suspend fun execute(
            profile: SshProfile,
            script: String,
            timeoutMillis: Long,
        ): RemoteCommandOutcome = outcome
    }

    private fun profile() =
        SshProfile(
            id = "vps-1",
            name = "VPS",
            host = "example.com",
            username = "root",
            authType = SshAuthType.PASSWORD,
            credentialRef = "ref",
        )

    private fun viewModel(
        executor: RemoteGitCommandExecutor,
        directory: String?,
        profile: SshProfile? = profile(),
    ) = RemoteGitViewModel(
        backend = NoopBackend(),
        directoryProvider = { directory },
        puller = RemoteGitPuller(executor),
        pusher = RemoteGitPusher(executor),
        profileProvider = { profile },
    )

    @Test
    fun `push success sets the success flag and output`() =
        runTest {
            val vm = viewModel(FakeExecutor(RemoteCommandOutcome.Completed("Everything up-to-date\n__TC_EXIT__0\n")), "/root/app")

            vm.push()

            assertTrue(vm.state.value.pushSuccess)
            assertTrue(vm.state.value.pushOutput.contains("Everything up-to-date"))
            assertFalse(vm.state.value.isPushing)
        }

    @Test
    fun `push failure surfaces a message`() =
        runTest {
            val vm = viewModel(FakeExecutor(RemoteCommandOutcome.Completed("boom\n__TC_EXIT__1\n")), "/root/app")

            vm.push()

            assertNotNull(vm.state.value.pushMessage)
            assertFalse(vm.state.value.pushSuccess)
        }

    @Test
    fun `push without a mapped path reports an error and does not run`() =
        runTest {
            val vm = viewModel(FakeExecutor(RemoteCommandOutcome.Completed("__TC_EXIT__0")), directory = null)

            vm.push()

            assertNotNull(vm.state.value.pushMessage)
            assertFalse(vm.state.value.pushSuccess)
        }

    @Test
    fun `push state carries no credential material`() =
        runTest {
            val vm = viewModel(FakeExecutor(RemoteCommandOutcome.Completed("__TC_EXIT__0")), "/root/app")

            vm.push()

            val text = vm.state.value.toString()
            assertTrue(!text.contains("password", ignoreCase = true))
            assertTrue(!text.contains("keyPem", ignoreCase = true))
        }
}
