package com.konprostart.tariffiacode.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "is the local server installed" / "is some other agent installed" split.
 *
 * Two places used to ask these questions by naming agents: the runtime status asked whether
 * OpenCode was installed, and the onboarding gate listed the three agents that could stand in for
 * it. Both lists would have been wrong the moment a fifth agent existed - and a new agent would
 * have been excluded from the second one, so a user who installed only that agent would have been
 * sent back through setup on every launch.
 */
class AgentServerRoleTest {
    @Test
    fun `exactly one agent provides the local server`() {
        val servers = LocalAgent.entries.filter { it.descriptor.providesLocalServer }

        assertEquals(
            "more than one agent claiming to be the local server makes the status ambiguous",
            1,
            servers.size,
        )
        assertEquals(LocalAgent.OPEN_CODE, LocalAgent.serverAgent())
    }

    @Test
    fun `the server agent is not among the agents that stand in for it`() {
        // The onboarding gate treats any of these as a usable setup. If the server agent were in
        // the list too, an install that had only ever fetched OpenCode would satisfy the "something
        // else is installed" question with the very agent the first question already asked about.
        assertFalse(LocalAgent.serverAgent() in LocalAgent.nonServerAgents())
    }

    @Test
    fun `every other agent stands in for the server, so none has to be listed anywhere`() {
        // The point of deriving this: Claude Code, Antigravity and Codex are here because they are
        // not the server agent, not because anyone wrote them down. An agent added tomorrow
        // appears here without this file changing.
        assertEquals(
            LocalAgent.entries.toSet() - LocalAgent.serverAgent(),
            LocalAgent.nonServerAgents().toSet(),
        )
        assertEquals(3, LocalAgent.nonServerAgents().size)
    }

    @Test
    fun `every agent that needs its own runtime gets one registered`() {
        // The application registers its agent targets in a map keyed by agent, and the registry is
        // built from that map's values. An agent added to the enum but not to that map compiles
        // fine and then simply has no runtime - selectable in the UI, absent from the registry.
        //
        // This cannot read the application's own map: building it needs a Context. What it can do
        // is name the agents that must appear in it, which is the list a new agent has to be added
        // to - OpenCode is absent because its runtime is the `localTarget` argument, not one of the
        // additional targets.
        val registered = setOf(LocalAgent.CLAUDE_CODE, LocalAgent.ANTIGRAVITY, LocalAgent.CODEX)

        assertEquals(
            "every agent other than the server agent needs an entry in the application's target map",
            LocalAgent.nonServerAgents().toSet(),
            registered,
        )
    }

    @Test
    fun `the server agent's binary path is where the runtime status looks for it`() {
        // computeStatus reads binaryPaths rather than a literal, so a descriptor whose path changed
        // would not leave the status checking a file that no longer exists.
        val server = LocalAgent.serverAgent()

        assertTrue(server.descriptor.binaryPaths.isNotEmpty())
        assertTrue(server.descriptor.binaryPaths.first().startsWith("/"))
        assertTrue(
            "the status resolves the path against the rootfs, so it must be guest-absolute",
            server.descriptor.binaryPaths.all { it.startsWith("/") },
        )
    }
}
