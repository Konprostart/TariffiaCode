package com.konprostart.tariffiacode.runtime.local

import com.konprostart.tariffiacode.runtime.AgentUserland
import com.konprostart.tariffiacode.runtime.LocalAgent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The phase contract the installer seam rests on.
 *
 * A provisioning pass runs download, then provision, then install, and each agent takes part in
 * whichever of those it needs. That ordering is the whole reason the seam exists - it is what lets
 * an agent's work be expressed without knowing what any other agent does - so these tests drive the
 * contract directly rather than through a full install, which needs a device.
 */
class AgentInstallerRegistryTest {
    @Test
    fun `agents run in registry order, not in the order they were selected`() {
        val registry = AgentInstallerRegistry(listOf(RecordingInstaller(LocalAgent.CODEX), RecordingInstaller(LocalAgent.OPEN_CODE)))

        // Selected in the opposite order on purpose: the registry decides, because the setup guide
        // renders one panel per agent and an install that reported its steps out of order would look
        // like it was going backwards.
        assertEquals(
            listOf(LocalAgent.CODEX, LocalAgent.OPEN_CODE),
            registry.forAgents(listOf(LocalAgent.OPEN_CODE, LocalAgent.CODEX)).map { it.agent },
        )
    }

    @Test
    fun `an agent with no installer is simply absent`() {
        val registry = AgentInstallerRegistry(listOf(RecordingInstaller(LocalAgent.OPEN_CODE)))

        assertEquals(listOf(LocalAgent.OPEN_CODE), registry.forAgents(LocalAgent.entries.toList()).map { it.agent })
    }

    @Test
    fun `an agent's installer knows which agent it installs`() {
        // The failure this guards: an agent added to LocalAgent without an installer would be
        // accepted by the app and then silently not installed at all. Only the installers whose
        // collaborators are constructible without an Android Context are built here; Claude Code and
        // Antigravity take one because they read an asset (the permission hook, the agent context),
        // and a unit test has no Context to give them.
        assertEquals(LocalAgent.OPEN_CODE, OpenCodeAgentInstaller().agent)
        assertEquals(LocalAgent.CODEX, CodexAgentInstaller().agent)
    }

    @Test
    fun `two installers for one agent is a configuration error`() {
        val failure =
            runCatching {
                AgentInstallerRegistry(listOf(RecordingInstaller(LocalAgent.CODEX), RecordingInstaller(LocalAgent.CODEX)))
            }.exceptionOrNull()

        assertNotNull("registering two installers for one agent must not be allowed", failure)
        assertTrue(failure!!.message!!.contains("CODEX"))
    }

    @Test
    fun `only an agent with a userland of its own gets a separate root`() {
        val staging = File("/tmp/staging")

        assertNull(
            "an agent on the shared rootfs must not be given one of its own",
            AgentInstallStep.userlandRootFor(staging, LocalAgent.OPEN_CODE),
        )
        // Named after the agent, so the path is a function of the agent rather than of which branch
        // created it - and it must stay the path this agent has always used.
        assertEquals(
            File(staging, "antigravity-rootfs"),
            AgentInstallStep.userlandRootFor(staging, LocalAgent.ANTIGRAVITY),
        )
    }

    @Test
    fun `the userland root is named after the agent that needs one`() {
        val staging = File("/tmp/staging")

        LocalAgent.entries
            .filter { it.descriptor.userland == AgentUserland.DEBIAN_ROOTFS }
            .forEach { agent ->
                val root = AgentInstallStep.userlandRootFor(staging, agent)
                assertNotNull("${agent.id} declares its own userland but was given no root", root)
                assertEquals("${agent.id}-rootfs", root!!.name)
            }
    }

    @Test
    fun `an artefact put in one phase comes back in the next`() =
        runTest {
            val step = step()

            step.put("archive", File("/tmp/opencode.tar.gz"))
            step.put("count", 7)

            assertEquals(File("/tmp/opencode.tar.gz"), step.get<File>("archive"))
            assertEquals(7, step.get<Int>("count"))
            assertNull("a missing key must be null rather than throw", step.get<File>("nope"))
            assertNull("a wrongly typed read must be null rather than throw", step.get<String>("count"))
        }

    @Test
    fun `an agent installs into the shared rootfs unless it declared one`() =
        runTest {
            val shared = File("/tmp/staging/rootfs")
            val step = step()
            assertSame("an agent on the shared userland must start on the shared rootfs", shared, step.installRoot)

            val own = File("/tmp/staging/antigravity-rootfs")
            step.useUserlandRoot(own)

            assertSame("provisioning a userland must redirect later phases into it", own, step.installRoot)
        }

    @Test
    fun `progress is attributed to the agent, and shared progress to no agent`() =
        runTest {
            val perAgent = mutableListOf<String>()
            val shared = mutableListOf<String>()
            val step =
                step(
                    onProgress = { _, label -> perAgent += label },
                    onSharedProgress = { _, label -> shared += label },
                )

            step.onProgress(0.5f, "installing")
            step.onSharedProgress(0.6f, "building the environment")

            assertEquals(listOf("installing"), perAgent)
            assertEquals(listOf("building the environment"), shared)
        }

    @Test
    fun `an agent's scratch space is named after it`() =
        runTest {
            // Inside the staging environment, so it is discarded with it if the install fails.
            assertEquals(File("/tmp/staging/codex-work"), step().workDirectory)
        }

    private fun step(
        onProgress: (Float, String) -> Unit = { _, _ -> },
        onSharedProgress: (Float, String) -> Unit = { _, _ -> },
    ) = AgentInstallStep(
        agent = LocalAgent.OPEN_CODE,
        label = { resource -> "label-$resource" },
        runtimeDirectory = File("/tmp/runtime"),
        stagingDirectory = File("/tmp/staging"),
        stagingRootfs = File("/tmp/staging/rootfs"),
        abi = "arm64-v8a",
        commandSuite =
            EmbeddedCommandSuite.Paths(
                home = File("/tmp/embedded/home"),
                tmp = File("/tmp/embedded/tmp"),
                nativeLibraryDirectory = File("/tmp/embedded/lib"),
                proot = File("/tmp/embedded/proot"),
                loader = File("/tmp/embedded/loader"),
                loader32 = File("/tmp/embedded/loader32"),
            ),
        accessCoordinator = LocalRuntimeAccessCoordinator(),
        httpClient = OkHttpClient(),
        downloader = VerifiedRuntimeDownloader(OkHttpClient()),
        cache = File("/tmp/runtime/cache"),
        manifest =
            LocalRuntimeManifest(
                schemaVersion = 1,
                runtimeVersion = "1",
                openCodeVersion = "1.0.0",
                alpineVersion = "3.21",
                port = 4096,
                architectures = emptyMap(),
            ),
        includeFullDevelopmentTools = false,
        onProgress = onProgress,
        onSharedProgress = onSharedProgress,
    )

    private class RecordingInstaller(override val agent: LocalAgent) : AgentInstaller {
        override suspend fun install(step: AgentInstallStep) = Unit
    }
}
