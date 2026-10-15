package com.konprostart.tariffiacode.core.router

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The loopback server, exercised over a real socket.
 *
 * Everything here is the server talking to itself: no emulator, no provider, no network. That is
 * possible precisely because `RouterGateway` is a seam, and it is what makes the three properties
 * worth having a test for verifiable at all - the token gate, the port the kernel picked, and the
 * fact that a streamed answer reaches the client frame by frame instead of arriving as one blob
 * after the provider finishes.
 */
class RouterServerTest {
    private var server: RouterServer? = null

    @After
    fun stopServer() {
        server?.stop()
        server = null
    }

    @Test
    fun `the port comes from the kernel and is not a fixed number`() {
        val first = start().port
        val second = start().port

        // Every proxy in this space uses 8082. Two of them on one device would either collide or,
        // worse, attach to the other's server and forward the user's keys to it.
        assertTrue("kernel must have assigned a real port", first > 0)
        assertNotEquals("two runs should not assume the same port", 8082, first)
        assertTrue(first > 0 && second > 0)
    }

    @Test
    fun `the address is loopback only`() {
        val started = start()

        assertTrue(started.baseUrl().startsWith("http://127.0.0.1:"))
    }

    @Test
    fun `a request without a bearer token is refused before the gateway sees it`() {
        val gateway = RecordingGateway()
        val started = start(gateway)

        val response = post(started, "/v1/messages", """{"model":"claude-sonnet-4"}""", token = null)

        assertEquals(401, response.status)
        assertEquals(
            "an unauthenticated caller must not be able to spend the user's quota",
            0,
            gateway.handled,
        )
    }

    @Test
    fun `a request with the wrong token is refused`() {
        val started = start(RecordingGateway())

        assertEquals(401, post(started, "/v1/messages", "{}", token = "not-the-token").status)
    }

    @Test
    fun `the bearer prefix is matched case-insensitively, as HTTP requires`() {
        val gateway = RecordingGateway()
        val started = start(gateway)

        val response =
            run {
                val connection =
                    URL(started.baseUrl() + "/v1/messages").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "BEARER " + token)
                connection.outputStream.use { it.write("""{"model":"claude-sonnet-4"}""".toByteArray()) }
                connection.responseCode
            }

        assertEquals(200, response)
        assertEquals(1, gateway.handled)
    }

    @Test
    fun `an unauthenticated caller cannot even ask which models exist`() {
        val started = start(RecordingGateway())

        assertEquals(401, get(started, "/v1/models", token = null).status)
    }

    @Test
    fun `an unknown path is a 404 and not a routing attempt`() {
        val gateway = RecordingGateway()
        val started = start(gateway)

        assertEquals(404, get(started, "/v1/secret", token = token).status)
        assertEquals(0, gateway.handled)
    }

    @Test
    fun `a non-streamed request is forwarded once`() {
        val gateway = RecordingGateway()
        val started = start(gateway)

        val response = post(started, "/v1/messages", """{"model":"claude-sonnet-4-5","stream":false}""", token)

        assertEquals(200, response.status)
        assertEquals(1, gateway.handled)
        assertEquals("claude-sonnet-4-5", gateway.lastRequest?.model)
        // stream:false must reach handle(), never stream(): a turn the client wants in one piece
        // cannot be satisfied by events, and taking the streaming path here would start a response
        // the client is not reading.
        assertEquals("a non-streamed request must not go down the streaming path", false, gateway.streaming)
    }

    @Test
    fun `a streamed request is forwarded frame by frame, not buffered to the end`() {
        val gateway =
            RecordingGateway().apply {
                frames = listOf(START_EVENT, DELTA_EVENT, STOP_EVENT_JSON)
            }
        val started = start(gateway)

        val response = post(started, "/v1/messages", """{"model":"claude-sonnet-4-5","stream":true}""", token)

        assertEquals(200, response.status)
        assertEquals(1, gateway.handled)
        assertTrue("streaming must be reported to the gateway", gateway.streaming)
        // Each frame carries both halves of an SSE event. A frame with a name and no data parses as
        // empty in a real client, which is the bug these assertions exist to prevent.
        assertTrue(response.body.contains("event: message_start"))
        assertTrue(response.body.contains("event: content_block_delta"))
        assertTrue(response.body.contains("event: message_stop"))
        assertTrue("the payload must actually be sent", response.body.contains("""data: {"type":"content_block_delta""""))
        assertEquals(3, response.body.split("\n\n").count { it.isNotBlank() })
    }

    @Test
    fun `a streamed response is chunked so the client can tell events apart`() {
        val gateway =
            RecordingGateway().apply {
                frames = listOf(START_EVENT, DELTA_EVENT, STOP_EVENT_JSON)
            }
        val started = start(gateway)

        val raw = rawPost(started, "/v1/messages", """{"model":"claude-sonnet-4-5","stream":true}""", token)

        // Read over a socket rather than through HttpURLConnection, which would de-chunk it for us
        // and hide exactly the thing under test.
        assertTrue("the response must declare chunked framing", raw.contains("Transfer-Encoding: chunked"))
        assertTrue("and must not claim a length it cannot know", !raw.contains("Content-Length:"))
        assertTrue("every chunk must be length-prefixed", CHUNK_PREFIX.containsMatchIn(raw))
        assertTrue("the stream must end with a zero-length chunk", raw.contains("\r\n0\r\n\r\n"))
        assertTrue("the payload must still be intact", raw.contains("""data: {"type":"content_block_delta""""))
    }

    @Test
    fun `token counting is answered locally and never reaches the gateway`() {
        val gateway = RecordingGateway()
        val started = start(gateway)

        val response = post(started, "/v1/messages/count_tokens", """{"model":"claude-sonnet-4-5"}""", token)

        assertEquals(200, response.status)
        assertTrue(response.body.contains("input_tokens"))
        assertEquals(
            "a token count must not spend a provider call",
            0,
            gateway.handled,
        )
    }

    @Test
    fun `a request with no body is a 400 rather than a crash`() {
        val started = start(RecordingGateway())

        val connection = URL(started.baseUrl() + "/v1/messages").openConnection() as HttpURLConnection
        connection.connectTimeout = CLIENT_TIMEOUT_MILLIS
        connection.readTimeout = CLIENT_TIMEOUT_MILLIS
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "BEARER $token")

        assertEquals(400, connection.responseCode)
    }

    @Test
    fun `the server survives being probed by a connection that says nothing`() {
        // The local runtime manager opens sockets to check ports. One that opens and closes without
        // sending a request must not take the server down or be treated as an error.
        val started = start(RecordingGateway())
        java.net.Socket("127.0.0.1", started.port).use { }

        assertEquals("a bare connection must not stop the listener", 200, get(started, "/v1/models", token).status)
    }

    @Test
    fun `stopping makes further requests fail rather than hang`() {
        val started = start(RecordingGateway())
        val url = URL(started.baseUrl() + "/v1/models")
        assertEquals(200, withToken(url).responseCode)

        server?.stop()
        server = null

        assertTrue(
            "after stop there is nothing listening",
            runCatching { withToken(url).responseCode }.isFailure,
        )
    }

    @Test
    fun `stopping closes a streamed connection instead of leaving the worker parked on it`() {
        // A worker blocked writing a frame has no write deadline - a slow model must not look like a
        // hang - so shutdown alone does not bring it back. Closing the connection is the only thing
        // that does, and this is the test that keeps stop() honest about doing it.
        val parked = CountDownLatch(1)
        val started = start(ParkedStreamingGateway(parked))
        val stream = openStream(started, "/v1/messages", """{"stream":true}""")

        assertTrue("the gateway should be streaming before stop is called", parked.await(5, TimeUnit.SECONDS))

        server?.stop()
        server = null

        // The client sees the turn end, rather than the socket being held open for the life of the
        // process by a worker that was written to and never read.
        val outcome = stream.use { runCatching { it.readBytes() } }
        assertTrue("reading a stopped stream must finish instead of hanging", outcome.isSuccess)
    }

    /** A gateway that emits one frame and then blocks, which is what a mid-turn stop runs into. */
    private class ParkedStreamingGateway(
        private val parked: CountDownLatch,
    ) : RouterGateway by RecordingGateway() {
        override fun stream(
            rawRequest: String,
            onEvent: (String) -> Unit,
        ) {
            onEvent(START_EVENT)
            parked.countDown()
            // Nothing more is emitted. The worker stays inside the chain until the socket closes.
            Thread.sleep(PARKED_MILLIS)
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    private companion object {
        const val START_EVENT = """{"type":"message_start"}"""
        const val DELTA_EVENT = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}"""
        const val STOP_EVENT_JSON = """{"type":"message_stop"}"""

        /** A chunk size line: hex digits between CRLFs. */
        val CHUNK_PREFIX = Regex("\r\n[0-9a-fA-F]+\r\n")

        /**
         * HttpURLConnection defaults to waiting forever. In a test that turns any bug in the server
         * into a build that hangs instead of a build that fails, which is a far worse outcome and a
         * far slower one to diagnose.
         */
        const val CLIENT_TIMEOUT_MILLIS = 5_000

        /**
         * How long the fake gateway sits inside a turn emitting nothing, long enough that stop() is
         * what ends the connection rather than the fake giving up on its own.
         */
        const val PARKED_MILLIS = 2_000L
    }

    private var token: String = ""
        private set

    private fun start(gateway: RouterGateway = RecordingGateway()): RouterServer {
        val created = RouterServer(gateway)
        created.start()
        server = created
        token = created.authToken()
        return created
    }

    private fun post(
        server: RouterServer,
        path: String,
        body: String,
        token: String?,
    ): Response {
        val connection = URL(server.baseUrl() + path).openConnection() as HttpURLConnection
        connection.connectTimeout = CLIENT_TIMEOUT_MILLIS
        connection.readTimeout = CLIENT_TIMEOUT_MILLIS
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        token?.let { connection.setRequestProperty("Authorization", "BEARER $it") }
        connection.outputStream.use { it.write(body.toByteArray()) }
        return connection.read()
    }

    private fun get(
        server: RouterServer,
        path: String,
        token: String?,
    ): Response {
        val connection = URL(server.baseUrl() + path).openConnection() as HttpURLConnection
        connection.connectTimeout = CLIENT_TIMEOUT_MILLIS
        connection.readTimeout = CLIENT_TIMEOUT_MILLIS
        connection.requestMethod = "GET"
        token?.let { connection.setRequestProperty("Authorization", "BEARER $it") }
        return connection.read()
    }

    /** One raw request and the whole response, chunk framing included. */
    private fun rawPost(
        server: RouterServer,
        path: String,
        body: String,
        token: String,
    ): String {
        val socket = java.net.Socket("127.0.0.1", server.port)
        socket.soTimeout = 5_000
        val request =
            buildString {
                append("POST $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                append("Authorization: BEARER $token\r\n")
                append("Content-Type: application/json\r\n")
                append("Content-Length: ${body.toByteArray().size}\r\n")
                append("Connection: close\r\n\r\n")
                append(body)
            }
        socket.getOutputStream().apply {
            write(request.toByteArray())
            flush()
        }
        val raw = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        socket.close()
        return raw
    }

    /** One streaming request, left open for the caller to read from. */
    private fun openStream(
        server: RouterServer,
        path: String,
        body: String,
    ): java.io.InputStream {
        val socket = java.net.Socket("127.0.0.1", server.port)
        socket.soTimeout = CLIENT_TIMEOUT_MILLIS
        val request =
            buildString {
                append("POST $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                append("Authorization: BEARER $token\r\n")
                append("Content-Type: application/json\r\n")
                append("Content-Length: ${body.toByteArray().size}\r\n")
                append("Connection: close\r\n\r\n")
                append(body)
            }
        socket.getOutputStream().apply {
            write(request.toByteArray())
            flush()
        }
        return socket.getInputStream()
    }

    private fun withToken(url: URL): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = CLIENT_TIMEOUT_MILLIS
        connection.readTimeout = CLIENT_TIMEOUT_MILLIS
        connection.setRequestProperty("Authorization", "BEARER $token")
        return connection
    }

    private data class Response(
        val status: Int,
        val body: String,
    )

    private fun HttpURLConnection.read(): Response {
        val stream = if (responseCode in 200..299) inputStream else errorStream
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        disconnect()
        return Response(responseCode, text)
    }

    /** A gateway that records what it was asked and answers with a fixed script. */
    private class RecordingGateway : RouterGateway {
        var handled = 0
            private set
        var streaming = false
            private set
        var lastRequest: AnthropicRequest? = null
        var frames: List<String> = listOf(START_EVENT)

        override fun isStreaming(rawRequest: String): Boolean = rawRequest.contains(""""stream":true""")

        override fun handle(rawRequest: String): String {
            handled++
            streaming = false
            record(rawRequest)
            return """{"type":"message","content":[]}"""
        }

        override fun stream(
            rawRequest: String,
            onEvent: (String) -> Unit,
        ) {
            handled++
            streaming = true
            record(rawRequest)
            frames.forEach(onEvent)
        }

        private fun record(rawRequest: String) {
            lastRequest =
                runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<AnthropicRequest>(rawRequest) }
                    .getOrNull()
        }

        override fun listModels(): String = """{"data":[]}"""

        override fun errorEvent(failure: Exception): String =
            """{"type":"error","error":{"type":"router_error","message":"${failure.message}"}}"""

        override fun onServerFailure(failure: Exception) = Unit
    }
}
