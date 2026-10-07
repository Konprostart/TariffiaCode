package com.konprostart.tariffiacode.runtime

import com.konprostart.tariffiacode.BuildConfig

/**
 * Flavor gate for the proprietary agent CLIs the app can download at runtime.
 *
 * Claude Code (Anthropic), Antigravity (Google) and Codex (OpenAI) are non-free binaries fetched on
 * user request; OpenCode is the free agent. The official F-Droid catalog forbids downloading
 * additional non-free executables, so the `fdroid` flavor compiles with
 * `BuildConfig.PROPRIETARY_AGENTS_ENABLED == false` and this object is the single place every
 * install/update/download entry point asks before doing anything. When it is off, proprietary agents
 * are removed from setup selections and install requests before any provisioning begins, so no
 * proprietary download can start; OpenCode, SSH/VPS and the remote workflow are untouched.
 *
 * [enabled] mirrors the build flag but is writable so tests can exercise both flavors from a single
 * compiled variant.
 */
object ProprietaryAgents {
    @Volatile
    var enabled: Boolean = BuildConfig.PROPRIETARY_AGENTS_ENABLED

    /** The only free agent; everything else is a non-free third-party CLI. */
    fun isProprietary(agent: LocalAgent): Boolean = agent != LocalAgent.OPEN_CODE

    /** Whether [agent] may be offered or installed in this build. */
    fun isAvailable(agent: LocalAgent): Boolean = enabled || !isProprietary(agent)

    /** Drops proprietary agents from [agents] when they are disabled in this build. */
    fun filter(agents: Collection<LocalAgent>): Set<LocalAgent> = agents.filterTo(LinkedHashSet()) { isAvailable(it) }

    /** The agents the setup guide may offer, in declaration order. */
    fun selectable(): List<LocalAgent> = LocalAgent.entries.filter { isAvailable(it) }

    /**
     * The agents a provisioning pass will actually install: the user's selection plus whatever the
     * current sandbox already records, with proprietary agents removed when disabled. This is what
     * [com.konprostart.tariffiacode.runtime.local.LocalRuntimeInstaller.install] hands to the
     * download steps, so an empty result (a proprietary-only request in an F-Droid build) fails its
     * "at least one agent" check before any download is attempted.
     */
    fun requestedForInstall(
        selected: Collection<LocalAgent>,
        existing: Collection<LocalAgent>,
    ): Set<LocalAgent> = filter(selected.toSet() + existing)
}
