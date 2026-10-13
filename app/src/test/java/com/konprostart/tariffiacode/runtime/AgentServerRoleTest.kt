package com.konprostart.tariffiacode.runtime

import com.konprostart.tariffiacode.agentTargetsOf
import com.konprostart.tariffiacode.core.api.OpenCodeAgent
import com.konprostart.tariffiacode.core.api.OpenCodeEvent
import com.konprostart.tariffiacode.core.api.OpenCodeHealth
import com.konprostart.tariffiacode.core.api.OpenCodeMessage
import com.konprostart.tariffiacode.core.api.OpenCodeSession
import com.konprostart.tariffiacode.core.api.PromptRequest
import com.konprostart.tariffiacode.core.api.ProviderCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "is the local server installed" / "is some other agent installed" split, and how agent
 * runtimes are registered.
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
    }

    @Test
    fun `the server agent's binary path is where the runtime status looks for it`() {
        // computeStatus reads binaryPaths rather than a literal, so a descriptor whose path changed
        // would not leave the status checking a file that no longer exists.
        val server = LocalAgent.serverAgent()

        assertTrue(server.descriptor.binaryPaths.isNotEmpty())
        assertTrue(server.descriptor.binaryPaths.first().startsWith("/"))
    }

    @Test
    fun `the registration keys come from what each target declares`() {
        // The application's target map used to be written as `LocalAgent.CLAUDE_CODE to
        // claudeCodeTarget` and so on - a second copy of the fact each target already declares, with
        // nothing to catch the two disagreeing, and RuntimeRegistry.targetFor would then resolve to
        // the wrong runtime for an agent. Keys are derived from `RuntimeTarget.agent` instead.
        val claude = FakeAgentTarget("claude-code-local", LocalAgent.CLAUDE_CODE)
        val antigravity = FakeAgentTarget("antigravity-local", LocalAgent.ANTIGRAVITY)
        val codex = FakeAgentTarget("codex-local", LocalAgent.CODEX)
        // The local OpenCode target and the VPS target declare no agent: the former is the
        // registry's `localTarget` argument rather than an additional target.
        val openCode = FakeAgentTarget("local-android", null)
        val vps = FakeAgentTarget("vps", null)

        val registered = agentTargetsOf(claude, antigravity, codex, openCode, vps)

        assertEquals(
            listOf(LocalAgent.CLAUDE_CODE, LocalAgent.ANTIGRAVITY, LocalAgent.CODEX),
            registered.keys.toList(),
        )
        assertSame(claude, registered[LocalAgent.CLAUDE_CODE])
        assertSame(antigravity, registered[LocalAgent.ANTIGRAVITY])
        assertSame(codex, registered[LocalAgent.CODEX])
    }

    @Test
    fun `a target registers under the agent it declares, not the slot it was built in`() {
        // The mistyped mapping this removes: a target claiming to be Codex while constructed where
        // Claude Code's used to be registered as Codex, so targetFor resolves it for Codex.
        val actuallyCodex = FakeAgentTarget("claude-code-local", LocalAgent.CODEX)

        assertEquals(listOf(LocalAgent.CODEX), agentTargetsOf(actuallyCodex).keys.toList())
    }
}

/**
 * A target carrying the agent it claims.
 *
 * The bodies are the ones the other fake targets in this package use: nothing here is exercised,
 * the point is the `agent` each instance declares.
 */
private class FakeAgentTarget(
    override val id: String,
    override val agent: LocalAgent?,
) : RuntimeTarget {
    override val type = RuntimeType.LOCAL
    override val kind = BackendKind.LOCAL
    override val displayName: String = id
    override val state = MutableStateFlow<RuntimeState>(RuntimeState.Disconnected)

    override suspend fun connect(): Result<OpenCodeHealth> = Result.success(OpenCodeHealth(true, "test"))

    override fun disconnect() = Unit

    override suspend fun listWorkspaces(): List<WorkspaceRef> = emptyList()

    override suspend fun health(): OpenCodeHealth = OpenCodeHealth(true, "test")

    override suspend fun listSessions(directory: String?): List<OpenCodeSession> = emptyList()

    override suspend fun createSession(
        title: String?,
        directory: String?,
    ): OpenCodeSession = error("unused")

    override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = emptyList()

    override suspend fun listProviders(): ProviderCatalog = ProviderCatalog()

    override suspend fun listAgents(): List<OpenCodeAgent> = emptyList()

    override suspend fun sendMessage(
        sessionId: String,
        request: PromptRequest,
    ) = Unit

    override suspend fun abortSession(sessionId: String): Boolean = true

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ): Boolean = true

    override fun events(): Flow<OpenCodeEvent> = emptyFlow()
}
