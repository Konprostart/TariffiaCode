package com.konprostart.tariffiacode.core.router

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A provider that misbehaves on demand, on a real socket.
 *
 * Written against [ServerSocket] rather than a JDK HTTP server because that is the only HTTP stack
 * this module's tests can rely on: the unit-test classpath has no `com.sun.net.httpserver`, and a
 * test helper that does not compile is worse than no helper.
 *
 * Providers are addressed by path, so one engine can be pointed at a chain of several of them at
 * once - which is the whole point, since the properties under test are about what happens between
 * providers.
 *
 * Raw sockets also buy the one thing a well-behaved server cannot do: [Reply.truncate], which
 * promises a Content-Length it does not deliver. That is exactly what a provider dying mid-turn looks
 * like on the wire, and there is no other way to reproduce it faithfully.
 */
internal class FakeProviderServer : AutoCloseable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val handlers = ConcurrentHashMap<String, (String) -> Reply>()
    private val hits = ConcurrentHashMap<String, Int>()
    private val bodies = ConcurrentHashMap<String, String>()
    private val running = AtomicBoolean(true)

    val port: Int get() = socket.localPort

    init {
        Thread({ acceptLoop() }, "fake-provider").apply {
            isDaemon = true
            start()
        }
    }

    /** Registers what [name] does. The argument is the request body it received. */
    fun on(
        name: String,
        handler: (String) -> Reply,
    ) {
        handlers[name] = handler
    }

    fun hits(name: String): Int = hits[name] ?: 0

    fun body(name: String): String = bodies[name] ?: ""

    override fun close() {
        running.set(false)
        runCatching { socket.close() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            runCatching { serve(client) }.onFailure { runCatching { client.close() } }
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = 5_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return closeQuietly(client)
        val path = requestLine.split(' ').getOrNull(1) ?: return closeQuietly(client)

        var contentLength = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0 && line.substring(0, separator).trim().equals("content-length", ignoreCase = true)) {
                contentLength = line.substring(separator + 1).trim().toIntOrNull() ?: 0
            }
        }

        val received = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val count = input.read(received, read, contentLength - read)
            if (count < 0) break
            read += count
        }
        val body = String(received, 0, read, Charsets.UTF_8)

        val provider = path.trimStart('/').substringBefore('/')
        hits.merge(provider, 1) { first, second -> first + second }
        bodies[provider] = body

        val reply = handlers[provider]?.invoke(body) ?: Reply.notFound()
        writeReply(client, reply)
    }

    private fun writeReply(
        client: Socket,
        reply: Reply,
    ) {
        val payload = reply.body.toByteArray()
        val promised = if (reply.truncate) payload.size + 512 else payload.size
        val head =
            buildString {
                append("HTTP/1.1 ").append(reply.status).append(' ').append(reply.reason()).append("\r\n")
                append("Content-Type: ").append(if (reply.status == 200) "text/event-stream" else "application/json").append("\r\n")
                append("Content-Length: ").append(promised).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
        val out = client.getOutputStream()
        out.write(head.toByteArray())
        out.write(payload)
        out.flush()
        closeQuietly(client)
    }

    private fun closeQuietly(client: Socket) {
        runCatching { client.close() }
    }

    private fun readLine(input: InputStream): String? {
        val line = java.io.ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (line.size() == 0) null else line.toString(Charsets.UTF_8)
            if (byte == '\n'.code) return line.toString(Charsets.UTF_8).removeSuffix("\r")
            line.write(byte)
        }
    }
}

/** What a fake provider sends back. */
internal data class Reply(
    val status: Int,
    val body: String,
    val truncate: Boolean = false,
) {
    fun reason(): String =
        when (status) {
            200 -> "OK"
            403 -> "Forbidden"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            else -> "Error"
        }

    companion object {
        /** A complete OpenAI-shaped answer, so the adapter has something real to parse. */
        fun answer(text: String) = Reply(200, """{"choices":[{"message":{"content":"$text"},"finish_reason":"stop"}]}""")

        /** One SSE frame per piece of text. */
        fun stream(vararg texts: String) = Reply(200, texts.joinToString("") { frame(it) })

        /** Says nothing at all, which is what makes failing over correct. */
        fun refuse(status: Int) = Reply(status, "")

        /**
         * Promises a length it will not deliver, then hangs up.
         *
         * With no frames this is a provider that answered 200 and then died before the turn began;
         * with one it is a provider that died after the user had already seen something.
         */
        fun die(vararg texts: String) = Reply(200, texts.joinToString("") { frame(it) }, truncate = true)

        fun notFound() = Reply(404, """{"error":"no such provider"}""")

        private fun frame(text: String) = "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"}}]}\n\n"
    }
}
