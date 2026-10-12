package com.konprostart.tariffiacode.runtime

import com.konprostart.tariffiacode.runtime.local.AntigravityManifest
import com.konprostart.tariffiacode.runtime.local.ClaudeCodeInstaller
import com.konprostart.tariffiacode.runtime.local.CodexInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what each agent's [AgentDescriptor] declares.
 *
 * The descriptors replaced decisions that used to be made implicitly - "anything that is not
 * OpenCode is proprietary", "anything outside a three-element set has no MCP toggle". Those defaults
 * were only ever correct for the four agents that exist today, and nothing failed when a fifth agent
 * inherited them. These tests are the safety net for the other half of that change: the descriptor
 * now has to be written down, and this file is what says the values written down are the ones the
 * app was already using. If a descriptor is edited, one of these fails.
 *
 * The binary-path checks are a drift guard. The descriptor does not feed the installers yet, so the
 * constants below are still the ones that install anything; comparing them keeps the declaration
 * honest until that wiring happens.
 */
class AgentDescriptorTest {
    @Test
    fun `agent ids are the ones persisted and restored across process boundaries`() {
        // Ids are written into installed-component metadata and passed back through a service Intent,
        // so they are part of the on-disk contract; renaming one silently reinstalls nothing.
        assertEquals(
            listOf("opencode", "claude-code", "antigravity", "codex"),
            LocalAgent.entries.map { it.descriptor.id },
        )
    }

    @Test
    fun `target ids match the ones the runtimes are registered under`() {
        assertEquals(
            listOf("local-android", "claude-code-local", "antigravity-local", "codex-local"),
            LocalAgent.entries.map { it.descriptor.targetId },
        )
    }

    @Test
    fun `every descriptor carries the resources and paths a row needs`() {
        LocalAgent.entries.forEach { agent ->
            val descriptor = agent.descriptor
            // 0 is not a real resource id, so a zeroed field means the descriptor was filled in
            // with a placeholder rather than an answer.
            assertTrue("${agent.id} has no display name", descriptor.displayNameRes != 0)
            assertTrue("${agent.id} has no icon", descriptor.iconRes != 0)
            assertTrue("${agent.id} has no target id", descriptor.targetId.isNotBlank())
            assertTrue("${agent.id} declares no binaries", descriptor.binaryPaths.isNotEmpty())
            descriptor.binaryPaths.forEach { path ->
                assertTrue("${agent.id} binary '$path' must be guest-absolute", path.startsWith("/"))
                assertFalse("${agent.id} binary '$path' must not be directory-only", path.endsWith("/"))
            }
        }
    }

    // The three classification decisions below used to be implicit. Each is pinned to the value the
    // app produced before the descriptor existed, which is what makes this a refactor and not a
    // behaviour change.

    @Test
    fun `proprietary classification matches what the app shipped`() {
        assertFalse(ProprietaryAgents.isProprietary(LocalAgent.OPEN_CODE))
        assertTrue(ProprietaryAgents.isProprietary(LocalAgent.CLAUDE_CODE))
        assertTrue(ProprietaryAgents.isProprietary(LocalAgent.ANTIGRAVITY))
        assertTrue(ProprietaryAgents.isProprietary(LocalAgent.CODEX))
    }

    @Test
    fun `the F-Droid gate withholds exactly the agents declared proprietary`() {
        val previous = ProprietaryAgents.enabled
        try {
            ProprietaryAgents.enabled = false
            // This is the failure mode the descriptor removes: the gate used to ask "is this the
            // free one", so an agent added anywhere but first would be withheld on this flavour
            // whether or not that was ever decided. It now follows what each agent declares.
            assertEquals(
                LocalAgent.entries.filter { !it.descriptor.proprietary }.map { it.id },
                ProprietaryAgents.selectable().map { it.id },
            )
            assertTrue(LocalAgent.OPEN_CODE in ProprietaryAgents.selectable())
        } finally {
            ProprietaryAgents.enabled = previous
        }
    }

    @Test
    fun `only OpenCode exposes a provider catalogue`() {
        assertTrue(LocalAgent.OPEN_CODE.descriptor.runtimeHasProviderCatalog)
        assertFalse(LocalAgent.CLAUDE_CODE.descriptor.runtimeHasProviderCatalog)
        assertFalse(LocalAgent.ANTIGRAVITY.descriptor.runtimeHasProviderCatalog)
        assertFalse(LocalAgent.CODEX.descriptor.runtimeHasProviderCatalog)
    }

    @Test
    fun `only OpenCode offers the MCP connect toggle and OAuth`() {
        LocalAgent.entries.forEach { agent ->
            val expected = agent == LocalAgent.OPEN_CODE
            assertEquals("${agent.id} connect toggle", expected, agent.descriptor.mcpSupportsConnectToggle)
            assertEquals("${agent.id} OAuth", expected, agent.descriptor.mcpSupportsOAuth)
        }
    }

    @Test
    fun `capabilities match what each runtime target used to declare`() {
        assertEquals(
            RuntimeCapabilities(
                permissions = true,
                providerModelList = true,
                abortsBeforeInterrupt = true,
                editMessages = true,
                diffCapable = true,
            ),
            LocalAgent.OPEN_CODE.descriptor.capabilities,
        )
        // permissions and questions are decided at runtime from the permission bridge, so they stay
        // at their default in the descriptor and ClaudeCodeTarget copies them over.
        assertEquals(
            RuntimeCapabilities(toolEvents = true, resume = true, diffCapable = true),
            LocalAgent.CLAUDE_CODE.descriptor.capabilities,
        )
        assertEquals(
            RuntimeCapabilities(toolEvents = true, forcesQueue = true),
            LocalAgent.ANTIGRAVITY.descriptor.capabilities,
        )
        assertEquals(
            RuntimeCapabilities(permissions = true, toolEvents = true, providerModelList = true),
            LocalAgent.CODEX.descriptor.capabilities,
        )
    }

    @Test
    fun `agents that declare no MCP config are exactly the ones that receive none`() {
        val without =
            LocalAgent.entries
                .filter { it.descriptor.mcp is AgentMcpProvisioning.None }
                .map { it.id }
        // Codex is driven as a JSON-RPC app server and is deliberately not provisioned with the
        // built-in browser/schedule servers; the other three each have a config file on the guest.
        assertEquals(listOf(LocalAgent.CODEX.id), without)
    }

    @Test
    fun `each MCP config path is the file the installer writes`() {
        fun mcpConfigFile(agent: LocalAgent): String? =
            (agent.descriptor.mcp as? AgentMcpProvisioning.AtConfigFile)?.path

        assertEquals("root/.config/opencode/opencode.json", mcpConfigFile(LocalAgent.OPEN_CODE))
        assertEquals("root/.claude.json", mcpConfigFile(LocalAgent.CLAUDE_CODE))
        assertEquals("root/.gemini/config/mcp_config.json", mcpConfigFile(LocalAgent.ANTIGRAVITY))
        assertNull(mcpConfigFile(LocalAgent.CODEX))
    }

    @Test
    fun `instruction files are declared only where the app writes one`() {
        // These are the paths ensureTariffiaCodeAgentContext writes, one per agent that has one.
        assertEquals("root/.config/opencode/tariffiacode-context.md", LocalAgent.OPEN_CODE.descriptor.instructionFile)
        assertEquals("root/.claude/CLAUDE.md", LocalAgent.CLAUDE_CODE.descriptor.instructionFile)
        assertEquals("root/.gemini/GEMINI.md", LocalAgent.ANTIGRAVITY.descriptor.instructionFile)
        assertNull(LocalAgent.CODEX.descriptor.instructionFile)
    }

    @Test
    fun `declared binaries agree with the installer constants`() {
        assertEquals(
            listOf(ClaudeCodeInstaller.CLAUDE_BINARY),
            LocalAgent.CLAUDE_CODE.descriptor.binaryPaths,
        )
        // Compared as full guest-absolute paths, not bare names: the directory is half of what the
        // descriptor asserts, and a bare-name comparison would keep passing if it moved to /opt.
        assertEquals(
            CodexInstaller.INSTALLED_BINARIES.map { "/${CodexInstaller.BIN_DIR}/$it" },
            LocalAgent.CODEX.descriptor.binaryPaths,
        )
        assertEquals(
            listOf("/${CodexInstaller.BIN_DIR}/${AntigravityManifest.BINARY_NAME}"),
            LocalAgent.ANTIGRAVITY.descriptor.binaryPaths,
        )
        // OpenCode has no shared constant yet - its path is written literally at each of the two
        // places that launch or update it - so there is nothing here to compare against.
        assertEquals(listOf("/usr/local/bin/opencode"), LocalAgent.OPEN_CODE.descriptor.binaryPaths)
    }

    @Test
    fun `only Antigravity needs a userland of its own`() {
        LocalAgent.entries.forEach { agent ->
            val expected = agent == LocalAgent.ANTIGRAVITY
            assertEquals(
                "${agent.id} userland",
                if (expected) AgentUserland.DEBIAN_ROOTFS else AgentUserland.ALPINE_STAGING_ROOTFS,
                agent.descriptor.userland,
            )
        }
    }

    @Test
    fun `each agent declares the install mechanism it is actually provisioned by`() {
        assertEquals(AgentInstallMechanism.DOWNLOADED_TARBALL, LocalAgent.OPEN_CODE.descriptor.installMechanism)
        assertEquals(AgentInstallMechanism.APK, LocalAgent.CLAUDE_CODE.descriptor.installMechanism)
        assertEquals(AgentInstallMechanism.GITHUB_RELEASE_TARBALL, LocalAgent.ANTIGRAVITY.descriptor.installMechanism)
        assertEquals(AgentInstallMechanism.NPM_TARBALL, LocalAgent.CODEX.descriptor.installMechanism)
    }
}
