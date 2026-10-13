package com.konprostart.tariffiacode.core.router

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
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
 * Failover tests prove the engine moves down a chain. These prove the chain it moves down is the right
 * one - that a Claude Opus request never quietly lands on a Haiku entry, that the model named in the
 * config is the model actually sent, and that the timeouts protecting the whole thing are the ones
 * claimed rather than the ones hoped for.
 */
class RouterRoutingTest {
    private lateinit var server: HttpServer
    private var port = 0
    private val hits = mutableMapOf<String, Int>()
    private val bodies = mutableMapOf<String, String>()

    @Before
    fun startServer() {
        hits.clear()
        bodies.clear()
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
    fun `a request is routed by the tier named in the model id`() {
        answer("sonnet-provider")
        answer("opus-provider")

        val subject =
            engine(
                RouterConfig(
                    listOf(
                        RouterTierChain(RouterTier.SONNET, listOf(RouterModel("sonnet-provider", "s", 200_000))),
                        RouterTierChain(RouterTier.OPUS, listOf(RouterModel("opus-provider", "o", 200_000))),
                    ),
                ),
            )

        subject.handle("""{"model":"claude-opus-4-1","messages":[]}""")

        assertEquals("an opus request must not be served by the sonnet chain", 0, hit("sonnet-provider"))
        assertEquals(1, hit("opus-provider"))
    }

    @Test
    fun `an unknown model falls back to the sonnet chain`() {
        answer("sonnet-provider")

        engine(
            RouterConfig(
                listOf(
                    RouterTierChain(RouterTier.SONNET, listOf(RouterModel("sonnet-provider", "s", 200_000))),
                    RouterTierChain(RouterTier.OPUS, listOf(RouterModel("opus-provider", "o", 200_000))),
                ),
            ),
        ).handle("""{"model":"something-unrecognisable","messages":[]}""")

        assertEquals("an unknown model should land somewhere usable, not nowhere", 1, hit("sonnet-provider"))
    }

    @Test
    fun `the chain is walked in the order the config declares`() {
        refuse("first", 429)
        refuse("second", 429)
        answer("third", "reached")

        engine(
            RouterConfig(
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
            ),
        ).handle("""{"model":"claude-sonnet-4-5","messages":[]}""")

        assertEquals(1, hit("first"))
        assertEquals(1, hit("second"))
        assertEquals("the first provider that answers ends the walk", 1, hit("third"))
    }

    @Test
    fun `the model named in the config is the model sent`() {
        answer("only")

        engine(
            RouterConfig(
                listOf(
                    RouterTierChain(RouterTier.SONNET, listOf(RouterModel("only", "deepseek-chat", 200_000))),
                ),
            ),
        ).handle("""{"model":"claude-sonnet-4-5","messages":[{"role":"user","content":"hi"}]}""")

        val body = bodies.getValue("only")
        assertTrue(
            "the provider must be asked for the configured model, not Claude's: $body",
            body.contains("\"model\":\"deepseek-chat\""),
        )
        assertTrue("and the user's message must survive translation", body.contains("hi"))
    }

    @Test
    fun `the stream flag is read from the request`() {
        val subject = engine(RouterConfig(listOf(RouterTierChain(RouterTier.SONNET))))
        assertTrue(subject.isStreaming("""{"model":"claude-sonnet-4-5","stream":true,"messages":[]}"""))
        assertFalse(subject.isStreaming("""{"model":"claude-sonnet-4-5","stream":false,"messages":[]}"""))
        assertFalse("an unreadable request is not a streaming one", subject.isStreaming("not json"))
    }

    @Test
    fun `the model list names every tier`() {
        val listed = engine(RouterConfig(emptyList())).listModels()

        RouterTier.entries.forEach { tier ->
            assertTrue("$tier missing from the model list: $listed", listed.contains(tier.name.lowercase()))
        }
        assertTrue(listed.contains("\"object\":\"model\""))
    }

    @Test
    fun `an error event is shaped the way the client parses`() {
        val event = engine(RouterConfig(emptyList())).errorEvent(IllegalStateException("provider \"x\" said no"))

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

        // Two tiers listing the same provider must not each keep their own view of whether it works,
        // or a Haiku request would happily pay for a provider Opus already proved is down.
        assertEquals(CircuitBreaker.Status.OPEN, breaker.statusOf("shared"))
        assertFalse(breaker.allows("shared"))
    }

    @Test
    fun `a tier with no chain serves nothing rather than falling through sideways`() {
        val subject = engine(RouterConfig(listOf(RouterTierChain(RouterTier.SONNET, listOf(RouterModel("only", "m", 200_000))))))

        val response = subject.handle("""{"model":"claude-haiku-4-5","messages":[]}""")

        assertNull(response)
        assertEquals("no chain, no provider, no request", 0, hit("only"))
    }

    // --- helpers ----------------------------------------------------------------------------

    private fun hit(name: String): Int = hits[name] ?: 0

    private fun engine(config: RouterConfig): RouterEngine {
        val keys = config.chains.flatMap { it.models }.map { it.provider }.distinct().associateWith { "key" }
        return RouterEngine(
            keys = { keys },
            config = { config },
            endpointFor = { ProviderEndpoint("http://127.0.0.1:$port/$it", "key") },
        )
    }

    private fun answer(
        name: String,
        text: String = "ok",
    ) = route(name) { exchange ->
        val body = """{"choices":[{"message":{"content":"$text"},"finish_reason":"stop"}]}"""
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun refuse(
        name: String,
        status: Int,
    ) = route(name) { exchange ->
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
    }

    private fun route(
        name: String,
        handler: (HttpExchange) -> Unit,
    ) {
        server.createContext("/$name") { exchange ->
            hits[name] = hit(name) + 1
            bodies[name] = exchange.requestBody.readBytes().decodeToString()
            runCatching { handler(exchange) }
                .onFailure { exchange.close() }
        }
    }
}
