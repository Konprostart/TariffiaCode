package com.konprostart.tariffiacode.runtime.local

import com.konprostart.tariffiacode.runtime.LocalAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * How `userlandRootfs` decides which agents got a rootfs of their own.
 *
 * The population is where the two conditions live - the agent is recorded as installed, and the
 * directory is actually there - and getting either wrong means a caller is pointed at a rootfs that
 * does not exist, or at a stale one that does.
 */
class UserlandRootfsTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `an installed agent with a directory present gets a rootfs`() {
        val environment = temporaryFolder.root
        File(environment, "antigravity-rootfs").mkdirs()

        assertEquals(
            setOf(LocalAgent.ANTIGRAVITY),
            LocalRuntimeInstaller.userlandRootfs(environment, metadata(LocalAgent.ANTIGRAVITY)).keys,
        )
    }

    @Test
    fun `a directory for an agent that was not installed is ignored`() {
        // The regression worth guarding: a Debian rootfs left on the device from an install that no
        // longer records Antigravity would otherwise be handed to a caller as this agent's root.
        val environment = temporaryFolder.root
        File(environment, "antigravity-rootfs").mkdirs()

        assertTrue(
            LocalRuntimeInstaller.userlandRootfs(environment, metadata()).isEmpty(),
        )
    }

    @Test
    fun `an installed agent whose directory is missing gets no rootfs`() {
        val environment = temporaryFolder.root

        assertTrue(
            LocalRuntimeInstaller.userlandRootfs(environment, metadata(LocalAgent.ANTIGRAVITY)).isEmpty(),
        )
    }

    @Test
    fun `the rootfs it hands back is the one inside the environment`() {
        val environment = temporaryFolder.root
        val expected = File(environment, "antigravity-rootfs")
        expected.mkdirs()

        assertEquals(
            expected,
            LocalRuntimeInstaller.userlandRootfs(environment, metadata(LocalAgent.ANTIGRAVITY))[LocalAgent.ANTIGRAVITY],
        )
    }

    private fun metadata(vararg agents: LocalAgent) =
        LocalRuntimeMetadata(
            version = "1.17.20",
            port = 4096,
            installedAt = 123,
            runtimeVersion = "1",
            abi = "arm64-v8a",
            components = agents.map { it.id }.toSet(),
            fullDevelopmentToolsInstalled = false,
            fullDebianDevelopmentToolsInstalled = false,
        )
}

/**
 * Where an agent runs, and the distinction this replaced.
 *
 * `InstalledRuntime` used to carry one nullable field per agent - `openCode`, `antigravityRootfs` -
 * and callers reached for them in two different ways that are not the same question:
 *
 *  - "which rootfs does this agent run in" (`antigravityRootfs ?: rootfs`), which is
 *    `rootfsFor(agent)`;
 *  - "was this agent provisioned a userland of its own", which only
 *    `AntigravityController.rehydrate` asks, and which used the field with no fallback.
 *
 * `rootfsFor` answers the first and cannot answer the second - it would fall back to the shared
 * rootfs and answer "is agy in the Alpine rootfs", which it never is. Both are pinned here,
 * because getting the second one wrong launches the agent against the wrong rootfs on a device, and
 * no unit test in the build would otherwise notice.
 */
class InstalledRuntimeRootsTest {
    private val rootfs = File("/env/rootfs")
    private val debianRootfs = File("/env/antigravity-rootfs")

    private fun installed(
        metadata: LocalRuntimeMetadata = metadata(),
        agentRootfs: Map<LocalAgent, File> = mapOf(LocalAgent.ANTIGRAVITY to debianRootfs),
    ) = LocalRuntimeInstaller.InstalledRuntime(
        metadata = metadata,
        commandSuite = suite,
        rootfs = rootfs,
        agentRootfs = agentRootfs,
    )

    @Test
    fun `an agent with a userland runs in it`() {
        assertSame(debianRootfs, installed().rootfsFor(LocalAgent.ANTIGRAVITY))
    }

    @Test
    fun `an agent without one runs in the shared rootfs`() {
        val runtime = installed()

        assertSame(rootfs, runtime.rootfsFor(LocalAgent.OPEN_CODE))
        assertSame(rootfs, runtime.rootfsFor(LocalAgent.CLAUDE_CODE))
        assertSame(rootfs, runtime.rootfsFor(LocalAgent.CODEX))
    }

    @Test
    fun `an install where nobody declared a userland has no roots of its own`() {
        // A sandbox holding only OpenCode and Claude Code: the map is empty, and every agent still
        // resolves to the shared rootfs rather than to null.
        val runtime = installed(agentRootfs = emptyMap())

        assertTrue(runtime.agentRootfs.isEmpty())
        LocalAgent.entries.forEach { assertSame(rootfs, runtime.rootfsFor(it)) }
    }

    @Test
    fun `having a userland is not the same as running in one`() {
        val withUserland = installed()
        assertTrue(withUserland.hasUserlandFor(LocalAgent.ANTIGRAVITY))
        assertFalse(withUserland.hasUserlandFor(LocalAgent.CODEX))

        val without = installed(agentRootfs = emptyMap())
        assertFalse(
            "rootfsFor must not be mistaken for the userland having been provisioned",
            without.hasUserlandFor(LocalAgent.ANTIGRAVITY),
        )
        // ...and it still answers the other question, which is what rehydrate's fallback needs.
        assertSame(rootfs, without.rootfsFor(LocalAgent.ANTIGRAVITY))
    }

    @Test
    fun `the binary resolves against the rootfs the agent runs in`() {
        assertEquals(
            File(debianRootfs, "usr/local/bin/agy"),
            installed().binaryFor(LocalAgent.ANTIGRAVITY),
        )
        assertEquals(
            File(rootfs, "usr/bin/claude"),
            installed(agentRootfs = emptyMap()).binaryFor(LocalAgent.CLAUDE_CODE),
        )
    }

    private fun metadata() =
        LocalRuntimeMetadata(
            version = "1.17.20",
            port = 4096,
            installedAt = 123,
            runtimeVersion = "1",
            abi = "arm64-v8a",
            components = setOf(LocalAgent.OPEN_CODE.id, LocalAgent.ANTIGRAVITY.id),
            fullDevelopmentToolsInstalled = false,
            fullDebianDevelopmentToolsInstalled = false,
        )

    private companion object {
        val suite =
            EmbeddedCommandSuite.Paths(
                home = File("/embedded/home"),
                tmp = File("/embedded/tmp"),
                nativeLibraryDirectory = File("/embedded/lib"),
                proot = File("/embedded/proot"),
                loader = File("/embedded/loader"),
                loader32 = File("/embedded/loader32"),
            )
    }
}
