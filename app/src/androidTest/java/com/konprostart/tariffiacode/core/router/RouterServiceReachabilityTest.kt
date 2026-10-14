package com.konprostart.tariffiacode.core.router

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * The reachability question, asked of a real Android runtime, across a real process boundary.
 *
 * Everything else about this router was verified on a desktop JVM or against our own fakes. What was
 * never verified is the one thing that decides whether it is usable: can a process that is *not* the
 * one holding the socket open a connection to it.
 *
 * In debug builds the service is declared with its own process, and the test asserts the two PIDs
 * differ before it makes any request. A test that merely ran inside the same process would pass even
 * if the socket were somehow private to it - which is the failure this exists to catch, and the
 * reason the PID check is not optional.
 *
 * The port is whatever the kernel assigned, read from the file the service writes rather than passed
 * in, because a hardcoded port would have turned a design problem into something that looked like a
 * connection problem.
 */
@RunWith(AndroidJUnit4::class)
class RouterServiceReachabilityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun stopRouter() {
        context.stopService(android.content.Intent(context, RouterService::class.java))
    }

    @Test
    fun theRouterAnswersARequestFromAnotherProcess() {
        val server = startRouter()

        assertTrue(
            "the test would prove nothing from inside the router's own process",
            server.pid != android.os.Process.myPid(),
        )

        val response = request(server, "/v1/models")

        assertEquals(
            "the model list did not come back. Response: $response. Service log: ${logcat()}",
            200,
            response.status,
        )
        assertTrue(
            "the model list should have come back: $response",
            response.body.contains("\"object\":\"model\""),
        )
    }

    @Test
    fun eachRunIssuesItsOwnToken() {
        val first = startRouter().token
        stopRouter()
        // stopService is asynchronous. Asking again before the service has actually gone would be
        // answered by the service that is still running, with the token it already had - and the
        // test would be comparing a token to itself.
        awaitRouterStopped()

        val second = startRouter().token

        // A fixed token meant one captured value opened every router that would ever run, and for
        // as long as that build was installed. 32 random bytes per run is what makes a token a
        // secret rather than a label.
        assertTrue("the token must not be a constant across runs", first != second)
        assertTrue("and it must be long enough to be worth guessing: ${first.length}", first.length >= 32)
    }

    @Test
    fun theRouterRefusesAProcessWithoutTheToken() {
        val server = startRouter()

        val response = request(server, "/v1/models", token = "wrong-token")

        assertEquals(
            "a caller with the wrong token must not be served. Response: $response",
            401,
            response.status,
        )
    }

    @Test
    fun aClaudeCodeShapedRequestTravelsTheChain() {
        val server = startRouter()

        // What Claude Code 2.1.296 actually sends, trimmed: a query string on the path, a system
        // prompt as an array, and a system role inside messages.
        val response =
            request(
                server,
                "/v1/messages?beta=true",
                method = "POST",
                body =
                    """
                    {"model":"claude-sonnet-4-5","max_tokens":100,"stream":false,
                    "system":[{"type":"text","text":"you are a coding agent"}],
                    "messages":[{"role":"system","content":[]},
                               {"role":"user","content":[{"type":"text","text":"hi"}]}]}
                    """.trimIndent().replace("\n", ""),
            )

        // The upstream is a placeholder key, so a gateway error is the expected end. What matters is
        // that the request was accepted, understood and routed: a 400 or 404 would mean the server
        // never got as far as asking a provider anything.
        assertEquals(
            "the request never reached the chain. Response: $response. Service log: ${logcat()}",
            502,
            response.status,
        )
    }

    /** Waits for the service to be gone, so the next start really is a new one. */
    private fun awaitRouterStopped() {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (!RouterService.endpointFile(context).exists()) return
            Thread.sleep(100)
        }
    }

    // --- helpers ----------------------------------------------------------------------------

    private class Running(
        val port: Int,
        val token: String,
        val pid: Int,
    )

    private class Reply(
        val status: Int,
        val body: String,
    )

    private fun startRouter(): Running {
        val file = RouterService.endpointFile(context)
        // Removed first, so what this waits for is the file this service just wrote.
        //
        // The router runs in its own process, so Android can end it without onDestroy() running -
        // and a file left behind names a port nothing is listening on. That is not only a test
        // hazard: an agent reading a stale endpoint gets a connection refused and no explanation.
        file.delete()
        context.startService(android.content.Intent(context, RouterService::class.java))
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (file.isFile) {
                val values =
                    file.readLines().mapNotNull { line ->
                        val value = line.substringAfter('=', "")
                        if (value.isBlank()) null else line.substringBefore('=') to value
                    }.toMap()
                val url = values["ANTHROPIC_BASE_URL"]
                val token = values["ANTHROPIC_AUTH_TOKEN"]
                val pid = values["ROUTER_PID"]
                if (url != null && token != null && pid != null) {
                    return Running(url.substringAfterLast(':').toInt(), token, pid.toInt())
                }
            }
            Thread.sleep(200)
        }
        throw AssertionError("the router never wrote its endpoint file")
    }

    private fun request(
        server: Running,
        path: String,
        method: String = "GET",
        token: String? = null,
        body: String? = null,
    ): Reply {
        val connection = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Authorization", "Bearer ${token ?: server.token}")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        return runCatching {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            Reply(status, stream?.bufferedReader()?.readText().orEmpty())
        }.getOrElse { Reply(-1, "threw ${it::class.simpleName}: ${it.message}") }
            .also { connection.disconnect() }
    }

    /** Whatever the service logged, so a failure says why rather than only that it happened. */
    private fun logcat(): String =
        runCatching {
            val process = ProcessBuilder("logcat", "-d", "-s", "RouterService:V").start()
            process.inputStream.bufferedReader().readText().takeLast(600)
        }.getOrDefault("logcat unavailable")
}
