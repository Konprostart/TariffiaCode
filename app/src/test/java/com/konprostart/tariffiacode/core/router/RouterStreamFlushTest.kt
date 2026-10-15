package com.konprostart.tariffiacode.core.router

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Three properties of the response path that a short turn never exercises.
 *
 * A one-word answer produces one event and closes immediately, so it passes on code that stalls,
 * drops a byte, or writes invalid JSON. Everything here is about what happens when the turn is long,
 * the provider is quiet in the middle of it, or one upstream frame carries more than one event.
 */
class RouterStreamFlushTest {
    /** A chunk plus a tool call in one upstream frame: two events, which used to be glued together. */
    @Test
    fun `two events in one payload become two frames rather than invalid JSON`() {
        val server = start(HoldingGateway())
        val first = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}"""
        val second = """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{"}}"""
        // Exactly what OpenAiCompatibleAdapter returns for a chunk holding both: no separator.
        val glued = first + second

        val body = post(server, glued)

        assertEquals(
            "two objects must not share one data line: $body",
            2,
            body.split("data: ").count { it.startsWith("{") },
        )
        assertTrue("both payloads must survive: $body", body.contains(""""text":"Hi""""))
        assertTrue("and so must the tool delta: $body", body.contains("partial_json"))
    }

    /**
     * The thinking phase is silence on the socket.
     *
     * The gateway produces nothing for longer than a client's idle tolerance, which is why the turn
     * used to die just as the answer was about to start. The assertion is that bytes still arrive
     * while the provider is quiet.
     */
    @Test
    fun `a provider that is quiet mid-turn still produces bytes downstream`() {
        val gateway = HoldingGateway()
        val server = start(gateway)
        val socket = openStream(server, """{"model":"claude-sonnet-4-5","stream":true}""")

        assertTrue("the gateway should have been entered", gateway.entered.await(10, TimeUnit.SECONDS))

        val reader = socket.getInputStream().bufferedReader()
        // Read past the status line and into the body. The first body byte is either the keep-alive
        // comment or a real event; either proves the turn is still being kept alive.
        var sawBody = false
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(40).toLong()
        while (!sawBody && System.currentTimeMillis() < deadline) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) sawBody = true
        }

        assertTrue("the stream must not sit silent through the whole thinking phase", sawBody)
        socket.close()
    }

    /** Every token is flushed on its own, so nothing waits for the buffer to fill. */
    @Test
    fun `a frame reaches the client while the turn is still open`() {
        val gateway = OneEventThenHoldingGateway()
        val server = start(gateway)
        val socket = openStream(server, """{"model":"claude-sonnet-4-5","stream":true}""")

        assertTrue("the gateway should have been entered", gateway.entered.await(10, TimeUnit.SECONDS))

        val reader = socket.getInputStream().bufferedReader()
        var payload: String? = null
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(20).toLong()
        while (payload == null && System.currentTimeMillis() < deadline) {
            val line = reader.readLine() ?: break
            if (line.startsWith("data: ")) payload = line
        }

        // The gateway is still holding the turn open, so this frame can only be here if the write
        // was flushed when it happened rather than at some later flush.
        assertTrue("the first token must be flushed before the turn ends", payload != null)
        assertTrue("and it must carry the delta: $payload", payload!!.contains("""text":"hi""""))

        socket.close()
    }

    // --- helpers ---------------------------------------------------------------------------

    /** Emits one event, then blocks, so the turn can be observed mid-flight. */
    private class HoldingGateway : RouterGateway {
        val entered = CountDownLatch(1)

        override fun isStreaming(rawRequest: String) = true

        override fun handle(rawRequest: String): String? = null

        override fun listModels(): String = "{\"data\":[]}"

        override fun errorEvent(failure: Exception): String = """{"type":"error","error":{"type":"router_error","message":"test"}}"""

        override fun stream(
            rawRequest: String,
            onEvent: (String) -> Unit,
        ) {
            entered.countDown()
            Thread.sleep(TimeUnit.SECONDS.toMillis(30))
            onEvent("""{"type":"message_stop"}""")
        }
    }

    private class OneEventThenHoldingGateway : RouterGateway {
        val entered = CountDownLatch(1)

        override fun isStreaming(rawRequest: String) = true

        override fun handle(rawRequest: String): String? = null

        override fun listModels(): String = "{\"data\":[]}"

        override fun errorEvent(failure: Exception): String = """{"type":"error","error":{"type":"router_error","message":"test"}}"""

        override fun stream(
            rawRequest: String,
            onEvent: (String) -> Unit,
        ) {
            entered.countDown()
            onEvent("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}""")
            Thread.sleep(TimeUnit.SECONDS.toMillis(30))
            onEvent("""{"type":"message_stop"}""")
        }
    }

    private var server: RouterServer? = null

    private fun start(gateway: RouterGateway): RouterServer {
        val created = RouterServer(gateway, "t")
        created.start()
        server = created
        return created
    }

    @After
    fun stopServer() {
        server?.stop()
        server = null
    }

    private fun openStream(
        server: RouterServer,
        body: String,
    ): Socket {
        val socket = Socket("127.0.0.1", server.port)
        socket.soTimeout = TimeUnit.SECONDS.toMillis(45).toInt()
        socket.getOutputStream().apply {
            write(request(body).toByteArray())
            flush()
        }
        return socket
    }

    /** Posts and reads the whole response, de-chunked by hand. */
    private fun post(
        server: RouterServer,
        body: String,
    ): String {
        val socket = openStream(server, body)
        val raw = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        socket.close()
        // Strip the chunk framing so the assertions read the payload rather than the transport.
        return raw
            .lineSequence()
            .filterNot { it.matches(HEX_SIZE) || it.isEmpty() }
            .joinToString("\n")
    }

    private fun request(body: String): String =
        buildString {
            append("POST /v1/messages HTTP/1.1\r\n")
            append("Host: 127.0.0.1\r\n")
            append("Authorization: BEARER t\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${body.toByteArray().size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
            append(body)
        }

    private companion object {
        val HEX_SIZE = Regex("^[0-9a-fA-F]+$")
    }
}
