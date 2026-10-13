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
        assertEquals("the gateway decides how to read the body", true, gateway.streaming)
    }

    @Test
    fun `a streamed request is forwarded frame by frame, not buffered to the end`() {
        val gateway =
            RecordingGateway().apply {
                frames = listOf("message_start", "content_block_delta", "message_stop")
            }
        val started = start(gateway)

        val response = post(started, "/v1/messages", """{"model":"claude-sonnet-4-5","stream":true}""", token)

        assertEquals(200, response.status)
        assertEquals(1, gateway.handled)
        assertTrue("streaming must be reported to the gateway", gateway.streaming)
        // The frames arrive as three separate SSE events, each terminated by a blank line.
        assertTrue(response.body.contains("event: message_start"))
        assertTrue(response.body.contains("event: content_block_delta"))
        assertTrue(response.body.contains("event: message_stop"))
        assertEquals(3, response.body.split("\n\n").count { it.isNotBlank() })
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

    // --- helpers ---------------------------------------------------------------------------

    private companion object {
        /**
         * HttpURLConnection defaults to waiting forever. In a test that turns any bug in the server
         * into a build that hangs instead of a build that fails, which is a far worse outcome and a
         * far slower one to diagnose.
         */
        const val CLIENT_TIMEOUT_MILLIS = 5_000
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
        var frames: List<String> = listOf("message_start")

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
