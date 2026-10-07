package com.konprostart.tariffiacode.runtime.local

/**
 * Chooses the sandbox mode Codex runs its own shell commands under.
 *
 * Codex's sandbox is layered *inside* this app's PRoot jail, which is a compatibility layer rather
 * than a security boundary against a same-UID agent. `workspace-write` is therefore the default: it
 * still confines Codex's file writes to the workspace and keeps network/process access restricted.
 * `danger-full-access` disables Codex's sandbox entirely, so it is only used after an explicit,
 * default-off user opt-in.
 */
object CodexSandboxPolicy {
    const val WORKSPACE_WRITE = "workspace-write"
    const val DANGER_FULL_ACCESS = "danger-full-access"

    /** The `sandbox_mode` to pass to Codex for the user's current preference. */
    fun sandboxMode(fullAccessEnabled: Boolean): String =
        if (fullAccessEnabled) DANGER_FULL_ACCESS else WORKSPACE_WRITE
}
