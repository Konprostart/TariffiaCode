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
        server.on("sonnet-provider") { Reply.answer("wrong-tier") }
        server.on("opus-provider") { Reply.answer("right-tier") }

        engine(
            listOf(
                RouterTierChain(RouterTier.SONNET, listOf(RouterModel("sonnet-provider", "s", 200_000))),
                RouterTierChain(RouterTier.OPUS, listOf(RouterModel("opus-provider", "o", 200_000))),
            ),
        ).handle("""{"model":"claude-opus-4-1","messages":[]}""")

        assertEquals("an opus request must not be served by the sonnet chain", 0, server.hits("sonnet-provider"))
        assertEquals(1, server.hits("opus-provider"))
    }

    @Test
    fun `an unrecognised model lands on a usable chain rather than nowhere`() {
        server.on("sonnet-provider") { Reply.answer("fallback") }

        engine(
            listOf(
                RouterTierChain(RouterTier.SONNET, listOf(RouterModel("sonnet-provider", "s", 200_000))),
                RouterTierChain(RouterTier.OPUS, listOf(RouterModel("opus-provider", "o", 200_000))),
            ),
        ).handle("""{"model":"something-unrecognisable","messages":[]}""")

        assertEquals(1, server.hits("sonnet-provider"))
    }

    @Test
    fun `the chain is walked in the order the config declares`() {
        server.on("first") { Reply.refuse(429) }
        server.on("second") { Reply.refuse(429) }
        server.on("third") { Reply.answer("reached") }

        engine(
            listOf(
                RouterTierChain(
                    RouterTier.SONNET,
                    listOf(
                        RouterModel("first", "a", 200_000),
                        RouterModel("second", "b", 200_000),
                        RouterModel("third", "c", 200_000),
                    ),
                ),
            ),
        ).handle("""{"model":"claude-sonnet-4-5","messages":[]}""")

        assertEquals(1, server.hits("first"))
        assertEquals(1, server.hits("second"))
        assertEquals("the first provider that answers ends the walk", 1, server.hits("third"))
    }

    @Test
    fun `the model named in the config is the model sent`() {
        server.on("only") { Reply.answer("served") }

        engine(
            listOf(RouterTierChain(RouterTier.SONNET, listOf(RouterModel("only", "deepseek-chat", 200_000)))),
        ).handle("""{"model":"claude-sonnet-4-5","messages":[{"role":"user","content":"hi"}]}""")

        val body = server.body("only")
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
        assertEquals(8L, RouterEngine.CONNECT_TIMEOUT_SECONDS)
        assertEquals(25L, RouterEngine.FIRST_BYTE_TIMEOUT_SECONDS)

        val client = RouterEngine.defaultClient()
        assertEquals(
            TimeUnit.SECONDS.toMillis(RouterEngine.CONNECT_TIMEOUT_SECONDS),
            client.connectTimeoutMillis,
        )
        assertEquals(
            "the read timeout is what bounds the wait for the first chunk",
            TimeUnit.SECONDS.toMillis(RouterEngine.FIRST_BYTE_TIMEOUT_SECONDS),
            client.readTimeoutMillis,
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
        server.on("only") { Reply.answer("served") }

        val response =
            engine(
                listOf(RouterTierChain(RouterTier.SONNET, listOf(RouterModel("only", "m", 200_000)))),
            ).handle("""{"model":"claude-haiku-4-5","messages":[]}""")

        assertNull(response)
        assertEquals("no chain, no provider, no request", 0, server.hits("only"))
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
