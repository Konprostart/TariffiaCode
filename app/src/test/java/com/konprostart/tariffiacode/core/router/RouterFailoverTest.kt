package com.konprostart.tariffiacode.core.router

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
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
 * There is no fake here on purpose. An earlier draft of this file tested a hand-written stand-in for
 * the engine, which meant it proved the stand-in worked and said nothing about the router - the two
 * would only ever agree by accident. So these drive [RouterEngine] over a real socket and observe the
 * only thing that matters from outside: which providers were contacted, and what reached the client.
 *
 * The pair that carries the weight is the third and second test. They cannot both pass unless the
 * engine distinguishes a provider that failed *before* saying anything from one that failed after.
 * An implementation that always failed over would pass the second and corrupt every mid-stream turn;
 * one that never failed over would pass the third and be useless.
 */
class RouterFailoverTest {
    private lateinit var server: HttpServer
    private var port = 0
    private val hits = mutableMapOf<String, Int>()

    @Before
    fun startServer() {
        hits.clear()
        server = HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.executor = null
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `a refusal before any bytes moves to the next provider`() {
        refuse("first", 429)
        answer("second", "from-second")

        val response =
            engine(listOf(model("first"), model("second"))).handle(
                """{"model":"claude-sonnet-4-5","messages":[{"role":"user","content":"hi"}]}""",
            )

        assertNotNull("a refusal that produced nothing must not end the turn", response)
        assertTrue(response!!.contains("from-second"))
        assertEquals(1, hit("first"))
        assertEquals(1, hit("second"))
    }

    @Test
    fun `a stream that dies before its first event is still failed over`() {
        // 200, then the connection ends without a single frame. The client has been told a response
        // is coming but has received none of it, so the turn has not started.
        truncate("silent", content = null)
        stream("second", "hello")

        val events =
            mutableListOf<String>()
        engine(listOf(model("silent"), model("second")))
            .stream("""{"model":"claude-sonnet-4-5","stream":true,"messages":[]}""") { events += it }

        assertEquals("only the provider that actually produced text may speak", 1, events.size)
        assertTrue(events.single().contains("hello"))
        assertEquals("the silent provider must have been tried once and abandoned", 1, hit("silent"))
        assertEquals(1, hit("second"))
    }

    @Test
    fun `once an event has reached the client there is no failover`() {
        // One frame lands, then the connection breaks mid-body. The user has seen this turn begin.
        truncate("partial", content = "partial-text")
        answer("second", "should-never-be-used")

        val events = mutableListOf<String>()
        val failure =
            runCatching {
                engine(listOf(model("partial"), model("second")))
                    .stream("""{"model":"claude-sonnet-4-5","stream":true,"messages":[]}""") { events += it }
            }.exceptionOrNull()

        assertEquals("the first event must still have reached the client", 1, events.size)
        assertTrue(events.single().contains("partial-text"))
        assertEquals(
            "the second provider must never be touched once the turn has begun",
            0,
            hit("second"),
        )
        assertNotNull("and the failure must be reported rather than swallowed", failure)
    }

    @Test
    fun `an exhausted chain reports rather than inventing an answer`() {
        refuse("first", 403)
        refuse("second", 500)

        val response =
            engine(listOf(model("first"), model("second"))).handle(
                """{"model":"claude-sonnet-4-5","messages":[]}""",
            )

        assertNull("nothing served the request, so there is nothing to return", response)
        assertEquals(1, hit("first"))
        assertEquals(1, hit("second"))
    }

    @Test
    fun `a provider with no key is never contacted`() {
        answer("keyless", "unused")
        answer("second", "served")

        val response =
            engine(listOf(model("keyless"), model("second")), keys = mapOf("second" to "k")).handle(
                """{"model":"claude-sonnet-4-5","messages":[]}""",
            )

        assertNotNull(response)
        assertEquals("a provider with no key has no adapter and must not be called", 0, hit("keyless"))
        assertEquals(1, hit("second"))
    }

    @Test
    fun `the keyless local provider is still usable`() {
        answer("ollama", "local-answer")

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
        refuse("flaky", 500)
        answer("healthy", "served")

        val subject = engine(listOf(model("flaky"), model("healthy")), breaker = breaker)
        val request = """{"model":"claude-sonnet-4-5","messages":[]}"""

        subject.handle(request)
        subject.handle(request)
        assertEquals(CircuitBreaker.Status.OPEN, breaker.statusOf("flaky"))

        val before = hit("flaky") ?: 0
        subject.handle(request)

        assertEquals("an open provider must not be paid for again", before, hit("flaky") ?: 0)
        assertTrue("the healthy provider must still serve", hit("healthy") != null && hit("healthy")!! > 0)
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
        assertEquals(CircuitBreaker.Status.CLOSED, breaker.statusOf("p"))

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

    // --- server helpers ---------------------------------------------------------------------

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
            endpointFor = { ProviderEndpoint("http://127.0.0.1:$port/$it", "key") },
        )
    }

    private fun hit(name: String): Int? = hits[name]

    /** Answers with a well-formed OpenAI response, so the adapter has something real to parse. */
    private fun answer(
        name: String,
        text: String,
    ) = route(name) { exchange ->
        val body = """{"choices":[{"message":{"content":"$text"},"finish_reason":"stop"}]}"""
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** Refuses outright: nothing is written, which is what makes failing over correct. */
    private fun refuse(
        name: String,
        status: Int,
    ) = route(name) { exchange ->
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
    }

    /** A single SSE frame carrying [content], then a clean end of stream. */
    private fun stream(
        name: String,
        content: String,
    ) = route(name) { exchange ->
        writeFrames(exchange, listOf(content))
    }

    /**
     * Promises more bytes than it delivers and closes, which is what a provider dying mid-turn looks
     * like on the wire. With [content] null it dies having said nothing at all.
     */
    private fun truncate(
        name: String,
        content: String?,
    ) = route(name) { exchange ->
        exchange.sendResponseHeaders(200, 4096)
        if (content != null) {
            exchange.responseBody.write(frame(content))
            exchange.responseBody.flush()
        }
        exchange.close()
    }

    private fun writeFrames(
        exchange: HttpExchange,
        contents: List<String>,
    ) {
        val body = contents.joinToString("") { frame(it) }.toByteArray()
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun frame(content: String) = "data: {\"choices\":[{\"delta\":{\"content\":\"$content\"}}]}\n\n"

    private fun route(
        name: String,
        handler: (HttpExchange) -> Unit,
    ) {
        server.createContext("/$name") { exchange ->
            hits[name] = (hits[name] ?: 0) + 1
            runCatching { handler(exchange) }
                .onFailure { exchange.close() }
        }
    }
}
