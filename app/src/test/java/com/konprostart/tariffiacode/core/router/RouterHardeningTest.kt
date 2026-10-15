package com.konprostart.tariffiacode.core.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The three properties this change is about, each asserted rather than assumed.
 *
 * None of them is visible in a short chat turn, which is why they survived: a key in a query string
 * works perfectly well until something logs the URL, an unbounded worker pool costs nothing until
 * there is more than a handful of idle connections, and a provider the user cannot name is a
 * feature nobody notices is missing until they want it.
 */
class RouterHardeningTest {
    /**
     * The key must not be reachable from anything that can be logged, retried or re-parsed.
     *
     * A header has no path into a URL. A query parameter has all of them, which is how it ended up
     * in request lines and, from there, in anything that prints a URL.
     */
    @Test
    fun `the Gemini key travels in a header and never in the path`() {
        val adapter = GeminiAdapter("SECRET-KEY-123")
        val request = AnthropicRequest(model = "claude-sonnet-4-5", messages = emptyList())
        val translated = adapter.buildRequest(request, RouterModel("gemini", "gemini-2.0-flash", 1_000_000))

        assertFalse(
            "the key must not be in the path: ${translated.path}",
            translated.path.contains("SECRET-KEY-123"),
        )
        assertFalse("and not as a query parameter either", translated.path.contains("key="))
        assertEquals(
            "the key goes in the header Google documents for it",
            "SECRET-KEY-123",
            adapter.extraHeaders()["x-goog-api-key"],
        )
    }

    /** A provider name this build has never heard of, addressed by the user, is a provider. */
    @Test
    fun `a provider the user addressed themselves gets an adapter`() {
        val custom = mapOf("my-private-gateway" to "https://llm.internal.example/v1")

        val adapter = ProviderAdapters.forProvider("my-private-gateway", emptyMap(), customProviders = custom.keys)

        assertNotNull("a configured name must not be skipped as unknown", adapter)
        assertEquals(RouterProviderKind.OPENAI_COMPATIBLE, adapter!!.kind)
    }

    /** Still no guessing: a name with neither a built-in address nor a saved one stays unusable. */
    @Test
    fun `a name nobody addressed is still not a provider`() {
        assertNull(ProviderAdapters.forProvider("who-knows", emptyMap()))
    }

    /**
     * The engine must accept the saved address, and dialled requests must actually arrive there.
     *
     * The address is proved by a local stand-in receiving the request rather than by reading a field
     * back: the failure being guarded against is a chain that *accepts* an entry and then sends it
     * somewhere else, or nowhere.
     */
    @Test
    fun `the engine sends a turn to a custom provider at the address the user saved`() {
        val standIn = ServerSocket(0)
        val base = "http://127.0.0.1:${standIn.localPort}"
        val requestLine = StringBuilder()
        val served = CountDownLatch(1)
        val responder =
            Thread {
                runCatching {
                    standIn.accept().use { socket ->
                        requestLine.append(socket.getInputStream().bufferedReader().readLine().orEmpty())
                        served.countDown()
                        val answer = """{"choices":[{"message":{"content":"hi"},"finish_reason":"stop"}]}"""
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: ${answer.length}\r\n\r\n".toByteArray())
                            write(answer.toByteArray())
                            flush()
                        }
                    }
                }
            }
        responder.isDaemon = true
        responder.start()

        val engine =
            RouterEngine(
                keys = { mapOf("my-private-gateway" to "k") },
                config = {
                    RouterConfig(
                        chains =
                            listOf(
                                RouterTierChain(
                                    tier = RouterTier.SONNET,
                                    models = listOf(RouterModel("my-private-gateway", "some-model", 1_000_000)),
                                ),
                            ),
                    )
                },
                customEndpoints = { mapOf("my-private-gateway" to base) },
            )

        val answer =
            runCatching {
                engine.handle("""{"model":"claude-sonnet-4-5","messages":[],"stream":false}""")
            }.getOrNull()

        // Awaited before the stand-in is closed, and asserted one stage at a time: "not dialled"
        // and "dialled but not understood" are different failures and must not share an assertion.
        val dialled = served.await(10, TimeUnit.SECONDS)
        responder.join(5_000)
        standIn.close()

        assertTrue(
            "the custom provider must actually have been dialled (request line seen: '$requestLine')",
            dialled,
        )
        assertEquals(
            "and it must be dialled with the OpenAI chat-completions path: $requestLine",
            "POST /v1/chat/completions HTTP/1.1",
            requestLine.toString(),
        )
        assertNotNull("and the turn must have produced an answer", answer)
    }

    /**
     * A flood of connections must not be able to spend the process.
     *
     * The router still answers afterwards, which is the property that matters: past the cap a
     * connection is closed unread, so a caller opening sockets costs it a descriptor for the length
     * of a handshake rather than a worker for the length of a turn.
     */
    @Test
    fun `connections past the cap are refused and the router still serves`() {
        val server = start()
        val held = mutableListOf<Socket>()
        try {
            repeat(PROBE_CONNECTIONS) {
                held += Socket("127.0.0.1", server.port).also { it.soTimeout = 2_000 }
            }

            assertEquals(
                "a flood must not take the router down",
                200,
                modelsStatus(server),
            )
        } finally {
            held.forEach { runCatching { it.close() } }
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun start(): RouterServer {
        val created = RouterServer(PlainGateway(), "t")
        created.start()
        server = created
        return created
    }

    private var server: RouterServer? = null

    @org.junit.After
    fun stopServer() {
        server?.stop()
        server = null
    }

    private fun modelsStatus(server: RouterServer): Int {
        val connection = URL("${server.baseUrl()}/v1/models").openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Authorization", "BEARER t")
        return runCatching { connection.responseCode }.getOrElse { -1 }.also { connection.disconnect() }
    }

    private class PlainGateway : RouterGateway {
        override fun isStreaming(rawRequest: String) = false

        override fun handle(rawRequest: String): String = """{"type":"message","content":[]}"""

        override fun listModels(): String = """{"data":[]}"""

        override fun stream(
            rawRequest: String,
            onEvent: (String) -> Unit,
        ) = Unit

        override fun errorEvent(failure: Exception): String = """{"type":"error"}"""

        override fun onServerFailure(failure: Exception) = Unit
    }

    private companion object {
        /** Comfortably past the cap, so the refusal path is definitely the one under test. */
        const val PROBE_CONNECTIONS = 40
    }
}
