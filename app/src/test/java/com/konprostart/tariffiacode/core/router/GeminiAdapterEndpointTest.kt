package com.konprostart.tariffiacode.core.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Google's two verbs, and the fact that they answer in two different shapes.
 *
 * This exists because the mistake it guards was made and shipped: the streaming verb was used for
 * every request. A streamed turn then worked and a short one produced nothing at all, which looks
 * like the router losing text rather than like a wrong URL - so it survived a release and a user
 * report before anyone looked at the path.
 *
 * Every case here is a round trip where it can be: the adapter's own output is fed back through the
 * adapter's own parser. A path test alone would pass with a verb and a parser that disagree, which
 * is the only way this bug can exist.
 */
class GeminiAdapterEndpointTest {
    private val adapter = GeminiAdapter("KEY")

    private fun model() = RouterModel("gemini", "gemini-2.0-flash", 1_000_000)

    // --- which verb -------------------------------------------------------------------------

    @Test
    fun `a non-streamed turn asks Google for the non-streaming verb`() {
        val path = adapter.buildRequest(request(stream = false), model()).path

        assertTrue(
            "a non-streamed turn must not ask for a stream: $path",
            path.endsWith(":generateContent"),
        )
        assertFalse("and must not carry the SSE switch: $path", path.contains("alt=sse"))
    }

    @Test
    fun `a streamed turn asks Google for the streaming verb with SSE`() {
        val path = adapter.buildRequest(request(stream = true), model()).path

        assertTrue("a streamed turn must use the streaming verb: $path", path.contains(":streamGenerateContent"))
        assertTrue(
            "and must ask for SSE, because that is the shape this adapter parses: $path",
            path.contains("alt=sse"),
        )
    }

    @Test
    fun `neither verb carries the key`() {
        listOf(false, true).forEach { stream ->
            val path = adapter.buildRequest(request(stream), model()).path
            assertFalse("stream=$stream leaked the key into $path", path.contains("KEY"))
            assertFalse("stream=$stream used a key parameter in $path", path.contains("key="))
        }
        assertEquals("KEY", adapter.extraHeaders()["x-goog-api-key"])
    }

    // --- round trips: what Google sends back must survive the parser -------------------------

    /**
     * The exact body `generateContent` returns for a non-streamed turn.
     *
     * Fed back through [GeminiAdapter.parseResponse], which is the path a real turn takes. If the
     * request had gone to the streaming verb this text would never arrive - it would arrive as `data:`
     * lines - and the assertion below would fail rather than the turn failing silently.
     */
    @Test
    fun `the object generateContent returns parses as an answer`() {
        val body =
            """
            {"candidates":[{"content":{"parts":[{"text":"Zdravo"}]},"finishReason":"STOP"}],
             "usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":3}}
            """.trimIndent()

        val parsed = adapter.parseResponse(200, body, "gemini-2.0-flash")

        assertNotNull("the object shape must parse", parsed)
        assertEquals("Zdravo", parsed!!.content.first().text)
        assertEquals("end_turn", parsed.stopReason)
        assertEquals(3, parsed.usage?.outputTokens)
    }

    /** The exact frame `streamGenerateContent?alt=sse` returns, one per line. */
    @Test
    fun `the frame streamGenerateContent returns parses as an event`() {
        val frame = """data: {"candidates":[{"content":{"parts":[{"text":"Zdravo"}]}}]}"""

        val event = adapter.parseStreamEvent(frame)

        assertNotNull("the SSE frame must parse", event)
        assertTrue("and carry the text: $event", event!!.contains(""""text":"Zdravo""""))
        assertTrue("as a content_block_delta: $event", event.contains("content_block_delta"))
    }

    // --- end to end through the engine ------------------------------------------------------

    /**
     * A non-streamed Gemini turn, dialled for real against a stand-in.
     *
     * This is the test that would have caught the shipped bug. It asserts on the request path the
     * engine actually put on the wire, and on an answer coming back, so a wrong verb fails here
     * rather than in a user's session.
     */
    @Test
    fun `a non-streamed Gemini turn reaches the non-streaming verb and returns an answer`() {
        val standIn = ServerSocket(0)
        val requestLine = StringBuilder()
        val served = CountDownLatch(1)
        val responder =
            Thread {
                runCatching {
                    standIn.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        requestLine.append(reader.readLine().orEmpty())
                        val contentLength =
                            generateSequence { reader.readLine() }
                                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                                ?.substringAfter(':')
                                ?.trim()
                                ?.toIntOrNull()
                                ?: 0
                        reader.readLine()
                        repeat(contentLength.coerceAtMost(65_536)) { reader.read() }
                        served.countDown()
                        val answer =
                            """{"candidates":[{"content":{"parts":[{"text":"Zdravo"}]},"finishReason":"STOP"}]}"""
                        socket.getOutputStream().apply {
                            write(
                                (
                                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                        "Content-Length: ${answer.length}\r\n\r\n"
                                ).toByteArray(),
                            )
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
                keys = { mapOf("gemini" to "KEY") },
                config = { RouterConfig(chains = listOf(sonnetChain())) },
                // Pointed at the stand-in rather than at googleapis.com: a test that reaches the
                // internet is a test that fails when the network is down and proves nothing else.
                endpointFor = { ProviderEndpoint("http://127.0.0.1:${standIn.localPort}") },
            )

        val answer =
            runCatching {
                engine.handle("""{"model":"claude-sonnet-4-5","messages":[],"stream":false}""")
            }.getOrNull()
        val reached = served.await(15, TimeUnit.SECONDS)
        responder.join(5_000)
        standIn.close()

        assertTrue("the provider must have been dialled", reached)
        assertEquals(
            "a non-streamed turn must use the non-streaming verb: $requestLine",
            "POST /v1beta/models/gemini-2.0-flash:generateContent HTTP/1.1",
            requestLine.toString(),
        )
        assertNotNull("and the turn must produce an answer rather than an empty one", answer)
        assertTrue("carrying what Google said: $answer", answer!!.contains("Zdravo"))
    }

    /** A streamed turn must use the streaming verb, dialled for real. */
    @Test
    fun `a streamed Gemini turn reaches the streaming verb`() {
        val standIn = ServerSocket(0)
        val requestLine = StringBuilder()
        val served = CountDownLatch(1)
        val responder =
            Thread {
                runCatching {
                    standIn.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        requestLine.append(reader.readLine().orEmpty())
                        served.countDown()
                        socket.getOutputStream().apply {
                            write(
                                (
                                    "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                                        "Transfer-Encoding: chunked\r\n\r\n"
                                ).toByteArray(),
                            )
                            val frame = """data: {"candidates":[{"content":{"parts":[{"text":"Zdravo"}]}}]}"""
                            val chunk = frame.length.toString(16)
                            write("$chunk\r\n$frame\r\n0\r\n\r\n".toByteArray())
                            flush()
                        }
                    }
                }
            }
        responder.isDaemon = true
        responder.start()

        val engine =
            RouterEngine(
                keys = { mapOf("gemini" to "KEY") },
                config = { RouterConfig(chains = listOf(sonnetChain())) },
                endpointFor = { ProviderEndpoint("http://127.0.0.1:${standIn.localPort}") },
            )

        val relayed = mutableListOf<String>()
        runCatching { engine.stream("""{"model":"claude-sonnet-4-5","messages":[],"stream":true}""") { relayed += it } }
        val reached = served.await(15, TimeUnit.SECONDS)
        responder.join(5_000)
        standIn.close()

        assertTrue("the provider must have been dialled", reached)
        assertEquals(
            "a streamed turn must use the streaming verb: $requestLine",
            "POST /v1beta/models/gemini-2.0-flash:streamGenerateContent?alt=sse HTTP/1.1",
            requestLine.toString(),
        )
        assertTrue("and the text must reach the caller: $relayed", relayed.any { it.contains("Zdravo") })
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun sonnetChain() =
        RouterTierChain(
            tier = RouterTier.SONNET,
            models = listOf(RouterModel("gemini", "gemini-2.0-flash", 1_000_000)),
        )

    private fun request(stream: Boolean) = AnthropicRequest(model = "claude-sonnet-4-5", stream = stream)
}
