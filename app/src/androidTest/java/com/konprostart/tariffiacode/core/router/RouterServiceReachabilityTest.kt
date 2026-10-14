package com.konprostart.tariffiacode.core.router

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The reachability question, asked of a real Android runtime.
 *
 * Everything else about this router was verified against a desktop JVM or against our own fakes. What
 * was never verified is the one thing that decides whether it is usable at all: that a process which
 * is *not* the app can open a connection to a loopback port the app opened. A test inside the app
 * cannot answer that - it would pass even if the socket were somehow private to it - so the request
 * here is made by an entirely separate process, launched through a shell.
 *
 * The port is deliberately whatever the kernel assigned, read from the file the service writes rather
 * than passed in, because a hardcoded port is what would have hidden this failure: it would have
 * looked like a connection problem instead of a design one.
 */
@RunWith(AndroidJUnit4::class)
class RouterServiceReachabilityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theRouterAnswersARequestFromAnotherProcess() {
        val server = startRouter()
        try {
            val response = fromASeparateProcess(server, "GET /v1/models HTTP/1.1")

            assertTrue(
                "a separate process got no HTTP response at all: $response",
                response.startsWith("HTTP/1.1 200"),
            )
            assertTrue(
                "the model list should have come back: $response",
                response.contains("\"object\":\"model\""),
            )
        } finally {
            context.stopService(android.content.Intent(context, RouterService::class.java))
        }
    }

    @Test
    fun theRouterRefusesAProcessWithoutTheToken() {
        val server = startRouter()
        try {
            val response = fromASeparateProcess(server, "GET /v1/models HTTP/1.1", token = "wrong-token")

            assertTrue(
                "a caller with the wrong token must not be served: $response",
                response.startsWith("HTTP/1.1 401"),
            )
        } finally {
            context.stopService(android.content.Intent(context, RouterService::class.java))
        }
    }

    @Test
    fun aClaudeCodeShapedRequestTravelsTheChain() {
        val server = startRouter()
        try {
            // The body Claude Code 2.1.296 actually sends, trimmed: a query string on the path, a
            // system prompt as an array, and a system role inside messages.
            val response =
                fromASeparateProcess(
                    server,
                    "POST /v1/messages?beta=true HTTP/1.1",
                    body =
                        """
                        {"model":"claude-sonnet-4-5","max_tokens":100,"stream":false,
                        "system":[{"type":"text","text":"you are a coding agent"}],
                        "messages":[{"role":"system","content":[]},
                                   {"role":"user","content":[{"type":"text","text":"hi"}]}]}
                        """.trimIndent().replace("\n", ""),
                )

            // The upstream here is a placeholder key, so this is expected to end in a gateway error.
            // What matters is that the request was accepted, understood and routed - a 400 or 404
            // would mean the server never got as far as asking a provider anything.
            assertTrue(
                "the request should have reached the chain, not been rejected: $response",
                response.startsWith("HTTP/1.1 200") || response.startsWith("HTTP/1.1 502"),
            )
            assertTrue(
                "a rejected body would mean the request never parsed: $response",
                !response.contains("400") && !response.contains("404"),
            )
        } finally {
            context.stopService(android.content.Intent(context, RouterService::class.java))
        }
    }

    // --- helpers ----------------------------------------------------------------------------

    private class Running(
        val port: Int,
        val token: String,
    )

    private fun startRouter(): Running {
        context.startService(android.content.Intent(context, RouterService::class.java))
        val file = RouterService.endpointFile(context)
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (file.isFile) {
                val values =
                    file.readLines().mapNotNull { line ->
                        val name = line.substringBefore('=')
                        val value = line.substringAfter('=', "")
                        if (value.isNotBlank()) name to value else null
                    }.toMap()
                val url = values["ANTHROPIC_BASE_URL"]
                val token = values["ANTHROPIC_AUTH_TOKEN"]
                if (url != null && token != null) return Running(url.substringAfterLast(':').toInt(), token)
            }
            Thread.sleep(200)
        }
        throw AssertionError("the router never wrote its endpoint file")
    }

    /**
     * Makes the request from a process that is not this one.
     *
     * `nc` is used rather than an HTTP client from the test, because an in-process client would prove
     * nothing: the question is whether something outside the app can reach the socket at all.
     */
    private fun fromASeparateProcess(
        server: Running,
        requestLine: String,
        token: String? = null,
        body: String? = null,
    ): String {
        val request =
            buildString {
                appendLine(requestLine)
                appendLine("Host: 127.0.0.1:${server.port}")
                appendLine("Authorization: Bearer ${token ?: server.token}")
                appendLine("Content-Type: application/json")
                if (body != null) appendLine("Content-Length: ${body.toByteArray().size}")
                appendLine("Connection: close")
                appendLine()
                if (body != null) append(body)
            }
        val command = listOf("nc", "127.0.0.1", server.port.toString())
        val process = ProcessBuilder(command).start()

        // Left open on purpose. Closing stdin is how nc is told the request is finished, and it then
        // tears the connection down - which races the server's reply. The first version closed it
        // immediately and lost the response for the larger of the two cases. The server sends
        // Connection: close, so reading to EOF terminates on its own.
        val stdin = process.outputStream
        stdin.write(request.toByteArray())
        stdin.flush()

        val response = process.inputStream.bufferedReader().readText()
        runCatching { stdin.close() }
        if (!process.waitFor(20, TimeUnit.SECONDS)) process.destroy()
        return response
    }
}
