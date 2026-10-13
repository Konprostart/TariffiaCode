package harness

import com.konprostart.tariffiacode.core.router.MapKeyStore
import com.konprostart.tariffiacode.core.router.ProviderEndpoint
import com.konprostart.tariffiacode.core.router.RouterConfigTable
import com.konprostart.tariffiacode.core.router.RouterEngine
import com.konprostart.tariffiacode.core.router.RouterServer
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetSocketAddress

/**
 * Runs the router on a plain JVM, with a mock upstream behind it.
 *
 * The point is not to re-test the router - its own tests do that. The point is to put the real
 * Claude Code CLI in front of the real server code and see what happens, because every property we
 * know about the wire format until now was read off our own source files rather than watched.
 *
 * The files compiled here are the production ones, not copies. The only file left out is the one that
 * cannot exist off a phone.
 *
 * Usage: harness <path-to-claude_tiers.json>
 */
object Harness {
    @JvmStatic
    fun main(args: Array<String>) {
        val table = File(args.firstOrNull() ?: "app/src/main/assets/claude_tiers.json")
        require(table.isFile) { "routing table not found: ${table.absolutePath}" }

        val loaded = RouterConfigTable.fromJson(table.readText())
        check(loaded.isUsable) { "routing table unusable: ${loaded.rejected}" }
        if (loaded.rejected.isNotEmpty()) println("[harness] validation dropped: ${loaded.rejected}")

        val upstream = MockUpstream()
        upstream.start()

        // Every provider in the table points at the same mock. Which provider "fails" is then a
        // property of the mock rather than of the network, which is what makes failover reproducible.
        val keys = MapKeyStore()
        RouterConfigTable.fromJson(table.readText()).config.chains
            .flatMap { it.models }
            .map { it.provider }
            .distinct()
            .filter { it.lowercase() != "ollama" && it.lowercase() != "lmstudio" }
            .forEach { keys.setKey(it, "mock-key") }

        val engine =
            RouterEngine(
                keys = { keys.snapshot() },
                config = { loaded.config },
                endpointFor = { ProviderEndpoint("http://127.0.0.1:${upstream.port}", "mock-key") },
            )

        val server = RouterServer(engine, token = TOKEN)
        val routerPort = server.start()

        println("ROUTER_PORT=$routerPort")
        println("ROUTER_TOKEN=$TOKEN")
        println("MOCK_UPSTREAM_PORT=${upstream.port}")
        println("READY")
        Runtime.getRuntime().addShutdownHook(
            Thread {
                server.stop()
                upstream.stop()
            },
        )
        // Nothing to do but stay alive; the client is what drives everything.
        Thread.currentThread().join()
    }

    private const val TOKEN = "harness-token-for-local-testing-only"

    /**
     * The provider the router talks to.
     *
     * Speaks OpenAI-compatible SSE, which is what most of the vendors in the table speak, so one mock
     * covers them all. It answers slowly and in pieces on purpose: the point of a first run is to see
     * frames arriving one at a time rather than as a single blob.
     */
    private class MockUpstream {
        private var server: HttpServer? = null
        var port = 0
            private set

        fun start() {
            val created = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server = created
            created.createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes().decodeToString()
                record(exchange.requestURI.path, body)
                val streaming =
                    runCatching {
                        Json.parseToJsonElement(body).jsonObject["stream"]?.jsonPrimitive?.content == "true"
                    }.getOrDefault(false)
                if (streaming) streamAnswer(exchange) else singleAnswer(exchange)
            }
            port = created.address.port
            created.start()
        }

        fun stop() = server?.stop(0)

        private fun record(
            path: String,
            body: String,
        ) {
            java.io.File("/tmp/opencode/harness/last-upstream.json").writeText(body)
            println(
                "[mock] $path model=" +
                    runCatching {
                        Json.parseToJsonElement(body).jsonObject["model"]?.jsonPrimitive?.content
                    }.getOrNull() + " stream=" +
                    runCatching {
                        Json.parseToJsonElement(body).jsonObject["stream"]?.jsonPrimitive?.content
                    }.getOrNull(),
            )
        }

        private fun singleAnswer(exchange: com.sun.net.httpserver.HttpExchange) {
            val answer =
                """{"id":"chatcmpl-mock","object":"chat.completion","created":0,"model":"mock","choices":[{"index":0,"message":{"role":"assistant","content":"Router reached the mock. This text travelled: Claude Code -> RouterServer -> RouterEngine -> mock."},"finish_reason":"stop"}],"usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}}"""
            reply(exchange, 200, answer)
        }

        private fun streamAnswer(exchange: com.sun.net.httpserver.HttpExchange) {
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                listOf("Router", " reached", " the", " mock", ".").forEach { piece ->
                    out.write("data: {\"choices\":[{\"delta\":{\"content\":\"$piece\"}}]}\n\n".toByteArray())
                    out.flush()
                    Thread.sleep(120)
                }
                out.write("data: [DONE]\n\n".toByteArray())
                out.flush()
            }
        }

        private fun reply(
            exchange: com.sun.net.httpserver.HttpExchange,
            status: Int,
            body: String,
        ) {
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
