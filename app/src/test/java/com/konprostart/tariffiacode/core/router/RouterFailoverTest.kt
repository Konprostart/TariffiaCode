package com.konprostart.tariffiacode.core.router

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The first-byte rule, proven against the real engine.
 *
 * There is no fake engine here on purpose. An earlier draft of this file tested a hand-written
 * stand-in for the router, which proved only that the stand-in agreed with itself and would have
 * agreed about the wrong thing just as confidently. So these drive [RouterEngine] over real sockets
 * and observe the only facts that matter from outside: which providers were contacted, and what
 * reached the client.
 *
 * The pair that carries the weight is the second and third test. They cannot both pass unless the
 * engine tells apart a provider that failed *before saying anything* from one that failed after.
 * An engine that always failed over would pass the second and corrupt every mid-stream turn; one
 * that never failed over would pass the third and be pointless.
 */
class RouterFailoverTest {
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
    fun `a refusal before any bytes moves to the next provider`() {
        server.on("deepseek") { Reply.refuse(429) }
        server.on("groq") { Reply.answer("from-second") }

        val response =
            engine(listOf(model("deepseek"), model("groq"))).handle(
                """{"model":"claude-sonnet-4-5","messages":[{"role":"user","content":"hi"}]}""",
            )

        assertNotNull("a refusal that produced nothing must not end the turn", response)
        assertTrue(response!!.contains("from-second"))
        assertEquals(1, server.hits("deepseek"))
        assertEquals(1, server.hits("groq"))
    }

    @Test
    fun `a stream that dies before its first event is still failed over`() {
        server.on("openrouter") { Reply.die() }
        server.on("groq") { Reply.stream("hello") }

        val events = mutableListOf<String>()
        engine(listOf(model("openrouter"), model("groq")))
            .stream("""{"model":"claude-sonnet-4-5","stream":true,"messages":[]}""") { events += it }

        assertEquals("only the provider that actually produced text may speak", 1, events.size)
        assertTrue(events.single().contains("hello"))
        assertEquals("the silent provider must have been tried once and abandoned", 1, server.hits("openrouter"))
        assertEquals(1, server.hits("groq"))
    }

    @Test
    fun `once an event has reached the client there is no failover`() {
        server.on("deepseek") { Reply.die("partial-text") }
        server.on("groq") { Reply.answer("should-never-be-used") }

        val events = mutableListOf<String>()
        val failure =
            runCatching {
                engine(listOf(model("deepseek"), model("groq")))
                    .stream("""{"model":"claude-sonnet-4-5","stream":true,"messages":[]}""") { events += it }
            }.exceptionOrNull()

        assertEquals("the first event must still have reached the client", 1, events.size)
        assertTrue(events.single().contains("partial-text"))
        assertEquals(
            "the second provider must never be touched once the turn has begun",
            0,
            server.hits("groq"),
        )
        assertNotNull("and the failure must be reported rather than swallowed", failure)
    }

    @Test
    fun `an exhausted chain reports rather than inventing an answer`() {
        server.on("deepseek") { Reply.refuse(403) }
        server.on("groq") { Reply.refuse(500) }

        val response =
            engine(listOf(model("deepseek"), model("groq"))).handle(
                """{"model":"claude-sonnet-4-5","messages":[]}""",
            )

        assertNull("nothing served the request, so there is nothing to return", response)
        assertEquals(1, server.hits("deepseek"))
        assertEquals(1, server.hits("groq"))
    }

    @Test
    fun `a provider with no key is never contacted`() {
        server.on("openrouter") { Reply.answer("unused") }
        server.on("groq") { Reply.answer("served") }

        val response =
            engine(listOf(model("openrouter"), model("groq")), keys = mapOf("groq" to "k")).handle(
                """{"model":"claude-sonnet-4-5","messages":[]}""",
            )

        assertNotNull(response)
        assertEquals("a provider with no key has no adapter and must not be called", 0, server.hits("openrouter"))
        assertEquals(1, server.hits("groq"))
    }

    @Test
    fun `the keyless local provider is still usable`() {
        server.on("ollama") { Reply.answer("local-answer") }

        val response =
            engine(listOf(RouterModel("ollama", "qwen3", 200_000)), keys = emptyMap()).handle(
                """{"model":"claude-sonnet-4-5","messages":[]}""",
            )

        assertNotNull("a local entry needs no key, which is the point of it", response)
        assertTrue(response!!.contains("local-answer"))
    }

    @Test
    fun `a provider that keeps failing is opened and then skipped`() {
        var now = 1_000L
        val breaker = CircuitBreaker(cooldownMillis = 60_000, failureThreshold = 2) { now }
        server.on("openrouter") { Reply.refuse(500) }
        server.on("groq") { Reply.answer("served") }

        val subject = engine(listOf(model("openrouter"), model("groq")), breaker = breaker)
        val request = """{"model":"claude-sonnet-4-5","messages":[]}"""

        subject.handle(request)
        subject.handle(request)
        assertEquals(CircuitBreaker.Status.OPEN, breaker.statusOf("openrouter"))

        val before = server.hits("openrouter")
        subject.handle(request)

        assertEquals("an open provider must not be paid for again", before, server.hits("openrouter"))
        assertTrue("the healthy provider must still serve", server.hits("groq") > 0)
    }

    @Test
    fun `an open provider gets exactly one probe after the cooldown`() {
        var now = 1_000L
        val breaker = CircuitBreaker(cooldownMillis = 60_000, failureThreshold = 1) { now }
        breaker.recordFailure("p")
        assertEquals(CircuitBreaker.Status.OPEN, breaker.statusOf("p"))

        now += 60_001

        assertTrue("the cooldown has passed, so one probe is admitted", breaker.allows("p"))
        assertEquals(CircuitBreaker.Status.HALF_OPEN, breaker.statusOf("p"))
        assertTrue(
            "a burst of concurrent requests must not all be let in at once",
            !breaker.allows("p"),
        )
    }

    @Test
    fun `a successful probe closes the breaker`() {
        var now = 1_000L
        val breaker = CircuitBreaker(cooldownMillis = 60_000, failureThreshold = 1) { now }
        breaker.recordFailure("p")

        now += 60_001
        assertTrue(breaker.allows("p"))
        breaker.recordSuccess("p")

        assertEquals(CircuitBreaker.Status.CLOSED, breaker.statusOf("p"))
        assertTrue("and it must serve again", breaker.allows("p"))
    }

    @Test
    fun `a failed probe re-opens the breaker immediately`() {
        var now = 1_000L
        val breaker = CircuitBreaker(cooldownMillis = 60_000, failureThreshold = 5) { now }
        repeat(4) { breaker.recordFailure("p") }
        assertEquals("the threshold has not been reached yet", CircuitBreaker.Status.CLOSED, breaker.statusOf("p"))

        now += 60_001
        assertTrue(breaker.allows("p"))
        breaker.recordFailure("p")

        assertEquals(
            "one failure from the probe is the evidence, whatever the threshold was",
            CircuitBreaker.Status.OPEN,
            breaker.statusOf("p"),
        )
        assertTrue("and it must not serve until the next cooldown", !breaker.allows("p"))
    }

    // --- helpers ----------------------------------------------------------------------------

    private fun model(provider: String) = RouterModel(provider, "test-model", 200_000)

    private fun engine(
        chain: List<RouterModel>,
        keys: Map<String, String> = chain.associate { it.provider to "key" },
        breaker: CircuitBreaker = CircuitBreaker(),
    ): RouterEngine {
        val config = RouterConfig(listOf(RouterTierChain(RouterTier.SONNET, chain)))
        return RouterEngine(
            keys = { keys },
            config = { config },
            breakers = breaker,
            endpointFor = { ProviderEndpoint("http://127.0.0.1:${server.port}/$it", "key") },
        )
    }
}
