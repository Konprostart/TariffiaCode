package com.konprostart.tariffiacode.runtime.local

import com.konprostart.tariffiacode.runtime.AgentUserland
import com.konprostart.tariffiacode.runtime.LocalAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun `every agent that declares a userland has one rootfs, and only those do`() {
        // The invariant that made the old per-agent fields wrong: a second agent declaring a
        // userland needs no new field here, and one that does not must not acquire a slot.
        val declaring =
            LocalAgent.entries.filter { it.descriptor.userland != AgentUserland.ALPINE_STAGING_ROOTFS }

        assertTrue("no agent declares a userland, so this test proves nothing", declaring.isNotEmpty())
        declaring.forEach { agent ->
            assertTrue("${agent.id} declares a userland but has no entry in the map", installed().hasUserlandFor(agent))
        }
        LocalAgent.entries
            .filterNot { it in declaring }
            .forEach { assertFalse("${agent.id} must not get a userland slot", installed().hasUserlandFor(it)) }
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
