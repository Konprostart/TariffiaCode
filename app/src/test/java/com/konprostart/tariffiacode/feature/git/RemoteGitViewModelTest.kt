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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteGitViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeBackend : OpenCodeBackend {
        override val id = "vps"
        override val displayName = "VPS"
        override val kind = BackendKind.REMOTE

        var infoDirectory: String? = null
        var statusDirectory: String? = null
        var diffDirectory: String? = null
        var fail = false

        override suspend fun health() = OpenCodeHealth(healthy = true, version = "1")

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

        override suspend fun vcsInfo(directory: String): OpenCodeVcsInfo {
            infoDirectory = directory
            if (fail) error("git unavailable")
            return OpenCodeVcsInfo(branch = "main")
        }

        override suspend fun vcsStatus(directory: String): List<OpenCodeFileChange> {
            statusDirectory = directory
            return listOf(OpenCodeFileChange(file = "src/a.kt", status = "M", added = 2, removed = 1))
        }

        override suspend fun vcsDiff(
            directory: String,
            mode: String,
            context: Int?,
        ): List<OpenCodeFileChange> {
            diffDirectory = directory
            return listOf(OpenCodeFileChange(file = "src/a.kt", patch = "@@ -1 +1 @@\n-old\n+new"))
        }
    }

    private fun viewModel(
        backend: OpenCodeBackend,
        directory: String?,
    ) = RemoteGitViewModel(backend) { directory }

    @Test
    fun `refresh uses the mapped remote path for git status and diff`() =
        runTest {
            val backend = FakeBackend()
            val vm = viewModel(backend, "/root/projects/app")

            vm.refresh()

            assertEquals("/root/projects/app", backend.infoDirectory)
            assertEquals("/root/projects/app", backend.statusDirectory)
            assertEquals("/root/projects/app", backend.diffDirectory)
            assertEquals("main", vm.state.value.branch)
            assertEquals(1, vm.state.value.changes.size)
            assertEquals(1, vm.state.value.diffs.size)
            assertNull(vm.state.value.error)
        }

    @Test
    fun `refresh without a mapping reports an error and never calls the backend`() =
        runTest {
            val backend = FakeBackend()
            val vm = viewModel(backend, null)

            vm.refresh()

            assertNotNull(vm.state.value.error)
            assertNull(backend.infoDirectory)
            assertTrue(vm.state.value.changes.isEmpty())
        }

    @Test
    fun `backend failure surfaces an error without crashing`() =
        runTest {
            val backend = FakeBackend().apply { fail = true }
            val vm = viewModel(backend, "/root/projects/app")

            vm.refresh()

            assertNotNull(vm.state.value.error)
        }

    @Test
    fun `git state carries no credential material`() =
        runTest {
            val backend = FakeBackend()
            val vm = viewModel(backend, "/root/projects/app")

            vm.refresh()

            val text = vm.state.value.toString()
            assertTrue(!text.contains("password", ignoreCase = true))
            assertTrue(!text.contains("keyPem", ignoreCase = true))
            assertTrue(!text.contains("passphrase", ignoreCase = true))
        }
}
