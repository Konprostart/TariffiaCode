package com.konprostart.tariffiacode.runtime

import com.konprostart.tariffiacode.R

/**
 * A coding agent that can be installed into the shared Android-local Linux sandbox.
 *
 * Every agent runs inside a PRoot userland; they differ in how they are provisioned (see
 * [AgentDescriptor.installMechanism]), in which userland they land in ([AgentDescriptor.userland])
 * and in how the app talks to them (a local HTTP server, a streaming JSON process, a JSON-RPC app
 * server or a one-shot `agy` invocation).
 *
 * Each entry carries a complete [AgentDescriptor], which is where the app's knowledge about the
 * agent lives. Adding an entry therefore means answering every question the app asks about an agent
 * - including whether it is free to download - instead of inheriting a fallthrough.
 *
 * The identity properties are delegated from the descriptor so existing call sites keep reading the
 * same way; they are not a second source of truth.
 */
enum class LocalAgent(val descriptor: AgentDescriptor) {
    OPEN_CODE(
        AgentDescriptor(
            id = "opencode",
            displayNameRes = R.string.agent_opencode_name,
            iconRes = R.drawable.ic_agent_opencode,
            targetId = "local-android",
            proprietary = false,
            installMechanism = AgentInstallMechanism.DOWNLOADED_TARBALL,
            userland = AgentUserland.ALPINE_STAGING_ROOTFS,
            binaryPaths = listOf("/usr/local/bin/opencode"),
            instructionFile = "root/.config/opencode/tariffiacode-context.md",
            mcp =
                AgentMcpProvisioning.AtConfigFile(
                    path = "root/.config/opencode/opencode.json",
                    jsonKey = "mcp",
                    entryStyle = AgentMcpEntryStyle.LOCAL_WITH_TIMEOUT,
                ),
            runtimeHasProviderCatalog = true,
            mcpSupportsConnectToggle = true,
            mcpSupportsOAuth = true,
            capabilities =
                RuntimeCapabilities(
                    permissions = true,
                    providerModelList = true,
                    abortsBeforeInterrupt = true,
                    editMessages = true,
                    diffCapable = true,
                ),
        ),
    ),
    CLAUDE_CODE(
        AgentDescriptor(
            id = "claude-code",
            displayNameRes = R.string.agent_claude_code_name,
            iconRes = R.drawable.ic_agent_claude,
            targetId = "claude-code-local",
            proprietary = true,
            installMechanism = AgentInstallMechanism.APK,
            userland = AgentUserland.ALPINE_STAGING_ROOTFS,
            binaryPaths = listOf("/usr/bin/claude"),
            instructionFile = "root/.claude/CLAUDE.md",
            mcp =
                AgentMcpProvisioning.AtConfigFile(
                    path = "root/.claude.json",
                    jsonKey = "mcpServers",
                    entryStyle = AgentMcpEntryStyle.STDIO,
                ),
            runtimeHasProviderCatalog = false,
            mcpSupportsConnectToggle = false,
            mcpSupportsOAuth = false,
            // permissions and questions are left at their default here on purpose: they are decided
            // at runtime from whether the permission bridge came up, so ClaudeCodeTarget copies them
            // over this set rather than declaring them.
            capabilities =
                RuntimeCapabilities(
                    toolEvents = true,
                    resume = true,
                    diffCapable = true,
                ),
        ),
    ),
    ANTIGRAVITY(
        AgentDescriptor(
            id = "antigravity",
            displayNameRes = R.string.agent_antigravity_name,
            iconRes = R.drawable.ic_agent_antigravity,
            targetId = "antigravity-local",
            proprietary = true,
            installMechanism = AgentInstallMechanism.GITHUB_RELEASE_TARBALL,
            userland = AgentUserland.DEBIAN_ROOTFS,
            binaryPaths = listOf("/usr/local/bin/agy"),
            instructionFile = "root/.gemini/GEMINI.md",
            mcp =
                AgentMcpProvisioning.AtConfigFile(
                    path = "root/.gemini/config/mcp_config.json",
                    jsonKey = "mcpServers",
                    entryStyle = AgentMcpEntryStyle.BARE_COMMAND,
                ),
            runtimeHasProviderCatalog = false,
            mcpSupportsConnectToggle = false,
            mcpSupportsOAuth = false,
            capabilities =
                RuntimeCapabilities(
                    toolEvents = true,
                    // Each turn is a fresh one-shot process, so "interrupting" one means killing it
                    // outright, which surfaced to the user as a crash rather than a cancellation.
                    forcesQueue = true,
                ),
        ),
    ),
    CODEX(
        AgentDescriptor(
            id = "codex",
            displayNameRes = R.string.agent_codex_name,
            iconRes = R.drawable.ic_agent_codex,
            targetId = "codex-local",
            proprietary = true,
            installMechanism = AgentInstallMechanism.NPM_TARBALL,
            userland = AgentUserland.ALPINE_STAGING_ROOTFS,
            binaryPaths = listOf("/usr/local/bin/codex", "/usr/local/bin/codex-code-mode-host"),
            // Codex is driven as a JSON-RPC app server and has no instruction file of its own in
            // the guest, so it is deliberately the one agent that receives no agent context.
            instructionFile = null,
            // Codex manages its own tool surface and is not provisioned with the built-in MCP
            // servers; declaring None keeps that explicit rather than an omission.
            mcp = AgentMcpProvisioning.None,
            runtimeHasProviderCatalog = false,
            mcpSupportsConnectToggle = false,
            mcpSupportsOAuth = false,
            capabilities =
                RuntimeCapabilities(
                    permissions = true,
                    toolEvents = true,
                    providerModelList = true,
                ),
        ),
    ),
    ;

    val id: String get() = descriptor.id

    val displayNameRes: Int get() = descriptor.displayNameRes

    val targetId: String get() = descriptor.targetId

    /** Marks which agent a row belongs to where the name does not fit - drawer chats, for one. */
    val iconRes: Int get() = descriptor.iconRes

    companion object {
        fun fromId(id: String): LocalAgent? = entries.firstOrNull { it.id == id }
    }
}
