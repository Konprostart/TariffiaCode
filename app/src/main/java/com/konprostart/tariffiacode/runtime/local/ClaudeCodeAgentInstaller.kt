package com.konprostart.tariffiacode.runtime.local

import com.konprostart.tariffiacode.R
import com.konprostart.tariffiacode.runtime.LocalAgent

/**
 * Claude Code: an Alpine package from Anthropic's own apk repository.
 *
 * The permission hook is installed here rather than with the shared guest capabilities because it is
 * Claude's: the script it writes is what routes Claude's permission prompts back to the app.
 */
class ClaudeCodeAgentInstaller(
    private val guestCapabilities: GuestCapabilities,
) : AgentInstaller {
    override val agent: LocalAgent = LocalAgent.CLAUDE_CODE

    override suspend fun install(step: AgentInstallStep) {
        step.onProgress(0.93f, step.label(R.string.install_step_installing_claude_code))
        ClaudeCodeInstaller.installInto(
            rootfs = step.installRoot,
            suite = step.commandSuite,
            runtimeDirectory = step.runtimeDirectory,
        )
        // Routed through the guest capabilities because the same hook is re-seeded onto every rootfs
        // of a runtime installed before this provisioning existed.
        guestCapabilities.installClaudePermissionHook(step.installRoot)
    }
}
