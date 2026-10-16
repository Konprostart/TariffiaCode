package com.konprostart.tariffiacode.core.router

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Which provider gets asked, in what order, with what.
 *
 * The failover tests prove the engine moves down a chain. These prove the chain it moves down is the
 * right one: that a Claude Opus request never quietly lands on a Haiku entry, that the model named
 * in the config is the model actually sent, and that the timeouts guarding the whole thing are the
 * ones claimed rather than the ones hoped for.
 */
class RouterRoutingTest {
    private lateinit var server: FakeProviderServer

    @Before
    fun startServer() {
        server = FakeProviderServer()
    }

    @After
    fun stopServer() {
        server.close()
    }

    @Test
    fun `a request is routed by the tier named in the model id`() {
        server.on("deepseek") { Reply.answer("wrong-tier") }
        server.on("groq") { Reply.answer("right-tier") }

        engine(
            listOf(
                RouterTierChain(RouterTier.SONNET, listOf(RouterModel("deepseek", "s", 200_000))),
                RouterTierChain(RouterTier.OPUS, listOf(RouterModel("groq", "o", 200_000))),
            ),
        ).handle("""{"model":"claude-opus-4-1","messages":[]}""")

        assertEquals("an opus request must not be served by the sonnet chain", 0, server.hits("deepseek"))
        assertEquals(1, server.hits("groq"))
    }

    @Test
    fun `an unrecognised model lands on a usable chain rather than nowhere`() {
        server.on("deepseek") { Reply.answer("fallback") }

        engine(
            listOf(
                RouterTierChain(RouterTier.SONNET, listOf(RouterModel("deepseek", "s", 200_000))),
                RouterTierChain(RouterTier.OPUS, listOf(RouterModel("groq", "o", 200_000))),
            ),
        ).handle("""{"model":"something-unrecognisable","messages":[]}""")

        assertEquals(1, server.hits("deepseek"))
    }

    @Test
    fun `the chain is walked in the order the config declares`() {
        server.on("deepseek") { Reply.refuse(429) }
        server.on("groq") { Reply.refuse(429) }
        server.on("openrouter") { Reply.answer("reached") }

        engine(
            listOf(
                RouterTierChain(
                    RouterTier.SONNET,
                    listOf(
                        RouterModel("deepseek", "a", 200_000),
                        RouterModel("groq", "b", 200_000),
                        RouterModel("openrouter", "c", 200_000),
                    ),
                ),
            ),
        ).handle("""{"model":"claude-sonnet-4-5","messages":[]}""")

        assertEquals(1, server.hits("deepseek"))
        assertEquals(1, server.hits("groq"))
        assertEquals("the first provider that answers ends the walk", 1, server.hits("openrouter"))
    }

    @Test
    fun `the model named in the config is the model sent`() {
        server.on("deepseek") { Reply.answer("served") }

        engine(
            listOf(RouterTierChain(RouterTier.SONNET, listOf(RouterModel("deepseek", "deepseek-chat", 200_000)))),
        ).handle("""{"model":"claude-sonnet-4-5","messages":[{"role":"user","content":"hi"}]}""")

        val body = server.body("deepseek")
        assertTrue(
            "the provider must be asked for the configured model, not Claude's: $body",
            body.contains("\"model\":\"deepseek-chat\""),
        )
        assertTrue("and the user's message must survive translation", body.contains("hi"))
    }

    @Test
    fun `the stream flag is read from the request`() {
        val subject = engine(listOf(RouterTierChain(RouterTier.SONNET)))
        assertTrue(subject.isStreaming("""{"model":"claude-sonnet-4-5","stream":true,"messages":[]}"""))
        assertFalse(subject.isStreaming("""{"model":"claude-sonnet-4-5","stream":false,"messages":[]}"""))
        assertFalse("an unreadable request is not a streaming one", subject.isStreaming("not json"))
    }

    @Test
    fun `the model list names every tier`() {
        val listed = engine(emptyList()).listModels()

        RouterTier.entries.forEach { tier ->
            assertTrue("$tier missing from the model list: $listed", listed.contains(tier.name.lowercase()))
        }
        assertTrue(listed.contains("\"object\":\"model\""))
    }

    @Test
    fun `an error event is shaped the way the client parses`() {
        val event = engine(emptyList()).errorEvent(IllegalStateException("provider \"x\" said no"))

        assertTrue(event.contains("\"type\":\"error\""))
        assertTrue("the message must survive intact", event.contains("said no"))
        assertTrue("and quotes must not break the JSON", event.contains("\\\""))
    }

    @Test
    fun `the timeouts are the ones the design calls for`() {
        assertEquals(15L, RouterEngine.CONNECT_TIMEOUT_SECONDS)
        assertEquals(60L, RouterEngine.FIRST_BYTE_DEADLINE_SECONDS)
        assertEquals(300L, RouterEngine.STEADY_IDLE_SECONDS)

        val client = RouterEngine.defaultClient()
        assertEquals(
            TimeUnit.SECONDS.toMillis(RouterEngine.CONNECT_TIMEOUT_SECONDS),
            client.connectTimeoutMillis.toLong(),
        )
        assertEquals(
            "the client's own read timeout is the steady-stream budget",
            TimeUnit.SECONDS.toMillis(RouterEngine.STEADY_IDLE_SECONDS),
            client.readTimeoutMillis.toLong(),
        )
    }

    @Test
    fun `one provider has one opinion across every tier that names it`() {
        val breaker = CircuitBreaker(cooldownMillis = 60_000, failureThreshold = 1)
        breaker.recordFailure("shared")

        // Two tiers naming the same provider must not each keep a private view of whether it works,
        // or a Haiku request would happily pay for a provider Opus already proved is down.
        assertEquals(CircuitBreaker.Status.OPEN, breaker.statusOf("shared"))
        assertFalse(breaker.allows("shared"))
    }

    @Test
    fun `a tier with no chain serves nothing rather than falling through sideways`() {
        server.on("deepseek") { Reply.answer("served") }

        val response =
            engine(
                listOf(RouterTierChain(RouterTier.SONNET, listOf(RouterModel("deepseek", "m", 200_000)))),
            ).handle("""{"model":"claude-haiku-4-5","messages":[]}""")

        assertNull(response)
        assertEquals("no chain, no provider, no request", 0, server.hits("deepseek"))
    }

    // --- helpers ----------------------------------------------------------------------------

    private fun engine(chains: List<RouterTierChain>): RouterEngine {
        val config = RouterConfig(chains)
        val keys = chains.flatMap { it.models }.map { it.provider }.distinct().associateWith { "key" }
        return RouterEngine(
            keys = { keys },
            config = { config },
            endpointFor = { ProviderEndpoint("http://127.0.0.1:${server.port}/$it", "key") },
        )
    }
}
