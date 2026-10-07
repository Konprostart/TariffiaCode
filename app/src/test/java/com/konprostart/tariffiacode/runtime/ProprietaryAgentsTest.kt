package com.konprostart.tariffiacode.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Proves the F-Droid flavor gate: with proprietary agents disabled, Claude Code, Antigravity and
 * Codex are neither offered nor part of an install request, so their download/install entry points
 * are unreachable. The `github` flavor (and every other build) keeps all four agents.
 */
class ProprietaryAgentsTest {
    private var previous: Boolean = true

    @Before
    fun capture() {
        previous = ProprietaryAgents.enabled
    }

    @After
    fun restore() {
        ProprietaryAgents.enabled = previous
    }

    @Test
    fun `an fdroid build offers only the free agent`() {
        ProprietaryAgents.enabled = false

        assertEquals(listOf(LocalAgent.OPEN_CODE), ProprietaryAgents.selectable())
        assertTrue(ProprietaryAgents.isAvailable(LocalAgent.OPEN_CODE))
        assertFalse(ProprietaryAgents.isAvailable(LocalAgent.CLAUDE_CODE))
        assertFalse(ProprietaryAgents.isAvailable(LocalAgent.ANTIGRAVITY))
        assertFalse(ProprietaryAgents.isAvailable(LocalAgent.CODEX))
    }

    @Test
    fun `an fdroid build strips proprietary agents from an install request`() {
        ProprietaryAgents.enabled = false

        val requested =
            ProprietaryAgents.requestedForInstall(
                selected = setOf(LocalAgent.OPEN_CODE, LocalAgent.CLAUDE_CODE, LocalAgent.ANTIGRAVITY),
                existing = listOf(LocalAgent.CODEX),
            )

        assertEquals(setOf(LocalAgent.OPEN_CODE), requested)
    }

    @Test
    fun `an fdroid build cannot provision a proprietary-only selection, so no download starts`() {
        ProprietaryAgents.enabled = false

        val requested =
            ProprietaryAgents.requestedForInstall(
                selected = setOf(LocalAgent.CLAUDE_CODE, LocalAgent.ANTIGRAVITY, LocalAgent.CODEX),
                existing = emptyList(),
            )

        // LocalRuntimeInstaller.install requires a non-empty agent set before any download, so this
        // selection fails that guard and nothing is ever fetched.
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `a non-fdroid build keeps every agent available`() {
        ProprietaryAgents.enabled = true

        assertEquals(LocalAgent.entries.toList(), ProprietaryAgents.selectable())
        assertEquals(
            setOf(LocalAgent.OPEN_CODE, LocalAgent.CLAUDE_CODE, LocalAgent.ANTIGRAVITY, LocalAgent.CODEX),
            ProprietaryAgents.requestedForInstall(
                selected = setOf(LocalAgent.OPEN_CODE, LocalAgent.CLAUDE_CODE, LocalAgent.ANTIGRAVITY, LocalAgent.CODEX),
                existing = emptyList(),
            ),
        )
    }
}
