package com.konprostart.tariffiacode.core.router

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A minimal HTTP/1.1 server for the router, on a loopback socket.
 *
 * Deliberately not Ktor. Three endpoints and a token check do not need a framework, and a framework
 * on a loopback socket is a large attack surface for a process that holds provider keys - the
 * routing, the auth and the content negotiation it would provide are all things this server can do
 * in a page, and nothing here has to be trusted with a path it was not written for.
 *
 * Two properties are load-bearing and easy to lose to a refactor:
 *
 *  - **Binding is to loopback only, never `0.0.0.0`.** A port reachable from the LAN means anyone on
 *    the same Wi-Fi can spend the user's API quota, and on some networks the router would be the
 *    only thing listening on a port that looks interesting.
 *  - **The port is chosen by the kernel (`ServerSocket(0)`), never hardcoded.** Every proxy in this
 *    space uses 8082; two of them on one device, or anything else already holding it, would either
 *    collide or - worse - silently attach to somebody else's server and forward the user's keys to
 *    it. The port is handed to the agent in the environment, so it does not need to be guessable.
 *
 * It serves on a daemon pool so the accept loop is never the thing that blocks, and every connection
 * gets a read timeout: an agent that opens a socket and says nothing must not be able to hold a
 * thread forever. Writes have no such deadline, because a slow model must not look like a hang - so
 * stopping the router closes the connections instead of waiting on them, which is the only way a
 * worker parked mid-stream is guaranteed to come back.
 */
class RouterServer(
    private val gateway: RouterGateway,
    private val token: String = newSessionToken(),
) {
    private val running = AtomicBoolean(false)

    /** Only used to read the event name back out of a frame the gateway has already built. */
    private val json = Json { isLenient = true }

    /**
     * Serialises [start] and [stop] against each other.
     *
     * They used to race: start() flipped `running` before it published `socket`, so a stop() landing
     * in that window completed against null fields and left a listening ServerSocket that reported
     * itself as stopped and that no later stop() could close. Everything both methods publish is now
     * written under one lock, in the order a reader needs it.
     */
    private val lifecycleLock = Any()

    @Volatile
    private var socket: ServerSocket? = null

    private var acceptExecutor: java.util.concurrent.ExecutorService? = null

    /**
     * Every connection currently being served.
     *
     * Tracked so [stop] can close them. A worker blocked writing a streamed answer to a client that
     * stopped reading is not interrupted by shutdown alone, so without this the sockets - and the
     * threads parked on them - outlive the router.
     */
    private val clients = ConcurrentHashMap.newKeySet<Socket>()

    private val connectionIds = AtomicLong()

    /** The port the server actually bound. Meaningful only while [isRunning]. */
    @Volatile
    var port: Int = 0
        private set

    /** The URL to hand the agent as `ANTHROPIC_BASE_URL`. */
    fun baseUrl(): String = "http://127.0.0.1:$port"

    fun isRunning(): Boolean = running.get()

    /**
     * The token this run requires.
     *
     * Goes to whoever hands the router's address to an agent - as `ANTHROPIC_AUTH_TOKEN` - and to
     * the server's own tests. Deliberately not part of [baseUrl]: a caller that only needs to know
     * where the router is should not also receive the thing that authorises spending the user's
     * quota.
     */
    fun authToken(): String = token

    /**
     * Binds and starts serving. Returns the bound port, or throws if the loopback port could not be
     * opened - there is no sensible fallback, because a router on an unexpected address is worse than
     * no router.
     */
    fun start(): Int {
        // Bind before anything is published, so a bind failure leaves no socket behind and no
        // half-started server. On Android (and under a killed-and-restarted service) the loopback
        // port chosen previously can still be in TIME_WAIT; binding with SO_REUSEADDR and a
        // short retry avoids a START_STICKY cycle that reports "running" over nothing - which is
        // the ECONNREFUSED the user sees when the listener never became live.
        val server = bindLoopback()
        val executor =
            Executors.newCachedThreadPool(
                ThreadFactory { runnable ->
                    Thread(runnable, "router-conn-${connectionIds.incrementAndGet()}").apply {
                        isDaemon = true
                    }
                },
            )
        synchronized(lifecycleLock) {
            check(!running.get()) { "Router is already running" }
            // Everything a concurrent stop() needs is published before `running` flips. Publishing
            // the flag first is what let a stop() complete against a half-built server and strand
            // the listener.
            socket = server
            port = server.localPort
            acceptExecutor = executor
            running.set(true)
        }
        // Launched after publication so the accept thread sees a consistent server, but outside
        // the lock so the thread is never (re)scheduled while it is held.
        Thread({ accept(server, executor) }, "router-accept").apply {
            isDaemon = true
        }.start()
        return server.localPort
    }

    /** Bind a loopback listener, tolerating a port that is still in TIME_WAIT. */
    private fun bindLoopback(): ServerSocket {
        val address = InetAddress.getByName(LOOPBACK)
        var lastFailure: java.io.IOException? = null
        repeat(BIND_ATTEMPTS) { attempt ->
            val candidate = ServerSocket()
            candidate.reuseAddress = true
            try {
                candidate.bind(InetSocketAddress(address, 0), BACKLOG)
                return candidate
            } catch (bindFailure: java.io.IOException) {
                lastFailure = bindFailure
                runCatching { candidate.close() }
                if (attempt < BIND_ATTEMPTS - 1) {
                    try {
                        Thread.sleep(BIND_RETRY_MILLIS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }
        throw lastFailure ?: java.io.IOException("could not bind the router listener")
    }

    /**
     * Stops accepting, closes the listener and closes every connection still being served.
     *
     * Connections are closed rather than left to finish. A streamed turn can be sitting in a write
     * with no deadline on it, and a worker that has already relayed a frame cannot be failed over
     * anyway, so waiting for it only keeps a socket and a thread alive on a router the user has
     * switched off. [stop] returns when nothing of this server is left running.
     */
    fun stop() {
        var listener: ServerSocket? = null
        var executor: java.util.concurrent.ExecutorService? = null
        synchronized(lifecycleLock) {
            if (running.compareAndSet(true, false)) {
                listener = socket
                executor = acceptExecutor
                socket = null
                acceptExecutor = null
                port = 0
                // Under the lock, against the add in accept(): closing is what unblocks a worker
                // parked on a read or a write, and it cannot block, so it costs nothing here.
                clients.forEach { client -> runCatching { client.close() } }
                clients.clear()
            }
        }
        if (listener == null && executor == null) return
        runCatching { listener?.close() }
        executor?.let { pool ->
            pool.shutdownNow()
            // Every connection is already closed, so this only waits for the workers to notice.
            // It is short deliberately: stop() is reached from the service's onDestroy on the main
            // thread, and blocking that thread is how stopping a service turns into an ANR.
            pool.awaitTermination(SHUTDOWN_GRACE_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private fun accept(
        server: ServerSocket,
        executor: java.util.concurrent.ExecutorService,
    ) {
        var consecutiveFailures = 0
        while (running.get() && !server.isClosed) {
            val client =
                try {
                    server.accept()
                } catch (closed: IOException) {
                    if (!running.get() || server.isClosed) return
                    // A transient accept failure - descriptors exhausted, a peer that vanished
                    // between SYN and accept - must not take the listener down for good. It used to
                    // end this loop and leave `running` true, so the status check - which reads a
                    // file the service wrote and never rewrites - kept reporting "running" over a
                    // socket nobody was accepting on.
                    consecutiveFailures++
                    if (consecutiveFailures > MAX_ACCEPT_FAILURES) {
                        gateway.onServerFailure(closed)
                        // The listener is gone even though its socket still completes handshakes out
                        // of the backlog, so nothing outside this object can notice: a liveness probe
                        // would connect successfully and report the router as serving. Tearing down
                        // here is the only way that stops being true, and it has to be the same
                        // teardown stop() does - leaving `running` true and the port published is
                        // exactly the state the status check cannot see through.
                        stop()
                        return
                    }
                    if (!sleepBeforeRetry()) return
                    continue
                }
            consecutiveFailures = 0
            // Tracked under the same lock stop() takes, so a connection cannot be added to a set
            // that has already been cleared and then served with nothing left to close it on.
            val tracked = synchronized(lifecycleLock) { running.get() && clients.add(client) }
            if (!tracked) {
                runCatching { client.close() }
                continue
            }
            val handedOff =
                runCatching {
                    executor.execute {
                        try {
                            client.use { serve(it) }
                        } finally {
                            clients.remove(client)
                        }
                    }
                }
            if (handedOff.isFailure) {
                // The pool could not take the connection. Closing it here is what stops the accept
                // loop from dying with the socket still open and the client waiting on it.
                clients.remove(client)
                runCatching { client.close() }
            }
        }
    }

    /** Returns false when the server was stopped while waiting to retry. */
    private fun sleepBeforeRetry(): Boolean {
        val deadline = System.nanoTime() + ACCEPT_RETRY_MILLIS * 1_000_000L
        while (running.get() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(ACCEPT_RETRY_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return running.get()
    }

    private fun serve(client: Socket) {
        runCatching {
            client.soTimeout = READ_TIMEOUT_MILLIS
            client.tcpNoDelay = true
            // One buffered stream for the header lines *and* the body. Reading the headers through a
            // BufferedReader and the body through the raw socket stream looks equivalent and is not:
            // the reader buffers ahead, and for any body under its 8 KiB buffer - which is every
            // Claude Code request - it swallows the body. The server then waits for Content-Length
            // bytes that have already been consumed and never answers at all.
            val input = BufferedInputStream(client.getInputStream())
            val request = readRequest(input) ?: return
            handle(request, client.getOutputStream())
        }.onFailure { error ->
            // A dropped connection is routine - the agent closes a socket it no longer needs - and
            // must not be reported as a server fault. Anything else is wrapped so the gateway's
            // contract can stay in terms of Exception.
            if (error !is IOException) {
                gateway.onServerFailure(error as? Exception ?: RuntimeException(error))
            }
        }
    }

    /**
     * Reads one CRLF-terminated line, in bytes.
     *
     * Deliberately not a BufferedReader: see the note in [serve] about why the two views of the
     * socket have to be the same one.
     */
    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (line.size() == 0) null else String(line.toByteArray(), CHARSET)
            if (byte == '\n'.code) return String(line.toByteArray(), CHARSET).removeSuffix("\r")
            line.write(byte)
            if (line.size() > MAX_HEADER_LINE_BYTES) return null
        }
    }

    /**
     * Reads one request line and its headers.
     *
     * Returns null for a connection that carried nothing - a health check, a port probe, a port that
     * was opened and closed. Those are not errors and must not be logged as ones.
     */
    private fun readRequest(body: InputStream): HttpRequest? {
        val requestLine = readLine(body) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0]
        val path = parts[1]
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(body) ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                // Lower-cased: header names are case-insensitive and Claude Code is not careful.
                headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
            }
        }
        return HttpRequest(method, path, headers, body)
    }

    private fun handle(
        request: HttpRequest,
        out: OutputStream,
    ) {
        if (!isAuthorized(request.headers["authorization"])) {
            // 401 before anything else touches the chain: an unauthenticated caller must not be able
            // to make the router spend the user's quota, or probe which providers are configured.
            writeJson(out, 401, errorBody("Invalid bearer token"))
            return
        }
        if (request.method == "GET" && request.path.startsWith(MODELS_PATH)) {
            writeJson(out, 200, gateway.listModels())
            return
        }
        if (request.path.startsWith(COUNT_TOKENS_PATH)) {
            val body =
                readBody(request) ?: run {
                    writeJson(out, 400, errorBody("Missing request body"))
                    return
                }
            // Not streamed and never failed over: a token count is a local operation, and letting
            // it fall through the chain would spend a provider call to answer a question arithmetic
            // can answer.
            writeJson(out, 200, gateway.countTokens(body))
            return
        }
        if (request.path.startsWith(MESSAGES_PATH)) {
            handleMessages(request, out)
            return
        }
        writeJson(out, 404, errorBody("Unknown path ${request.path}"))
    }

    private fun handleMessages(
        request: HttpRequest,
        out: OutputStream,
    ) {
        val raw = readBody(request)
        if (raw == null) {
            writeJson(out, 400, errorBody("Missing request body"))
            return
        }
        if (gateway.isStreaming(raw)) {
            streamResponses(request, raw, out)
        } else {
            val response = gateway.handle(raw)
            if (response == null) {
                writeJson(out, 502, errorBody("No provider in the chain could serve this request"))
            } else {
                writeJson(out, 200, response)
            }
        }
    }

    /**
     * Forwards a streaming request, writing each translated frame as it arrives.
     *
     * Nothing is buffered on the way out: an SSE client shows the turn as it is produced, and a
     * buffer here would make a long first-token model look like it had hung.
     *
     * The chain has already committed to a provider by the time the first frame is written, so a
     * failure after that point cannot be failed over - it is reported to the client as an Anthropic
     * error event and the connection ends. That boundary is the whole reason this is separate from the
     * non-streaming path.
     */
    private fun streamResponses(
        request: HttpRequest,
        raw: String,
        out: OutputStream,
    ) {
        // No Content-Length: the length is not knowable until the model stops, which may be never.
        // Chunked is the alternative that still lets the client tell where events begin and end.
        writeStatus(out, 200, "text/event-stream", chunked = true)
        val stream = ChunkedStream(out)
        try {
            var sawStop = false
            gateway.stream(raw) { event ->
                if (event.contains(STOP_EVENT)) sawStop = true
                stream.write(sseFrame(event))
            }
            // Only if the chain did not already close its own stream: two message_stop events make a
            // client that is counting events, or keying off the terminal one, behave oddly.
            if (!sawStop) {
                stream.write(sseFrame(DONE_EVENT))
            }
            stream.finish()
        } catch (failure: IOException) {
            // The agent closed the connection mid-turn. Nothing to report to a caller that left.
        } catch (failure: Exception) {
            // Report it in the shape the client parses rather than dropping the stream silently.
            runCatching {
                stream.write(sseFrame(gateway.errorEvent(failure)))
                stream.finish()
            }
        }
    }

    /**
     * One SSE frame: the event name, and the event itself.
     *
     * Both lines matter. The gateway hands over a complete Anthropic event object, and sending it as
     * the event *name* - which is what this used to do - left the client with a name and no payload,
     * so every frame parsed as empty and the stream was unwatchable.
     *
     * The name is taken from the payload rather than asked for separately, so the two cannot drift.
     * A payload with no recognisable type still goes out as data, because losing the payload is worse
     * than losing the name: a client that keys off `type` can recover, one left with no data cannot.
     */
    private fun sseFrame(payload: String): ByteArray {
        val name = runCatching { json.parseToJsonElement(payload).jsonObject["type"]?.jsonPrimitive?.content }.getOrNull()
        val head = if (name.isNullOrBlank()) "" else "event: $name\n"
        return "${head}data: $payload\n\n".toByteArray(CHARSET)
    }

    /**
     * Frames writes as HTTP/1.1 chunked transfer-encoding.
     *
     * Each write is length-prefixed so the client can find the next event, and the zero-length chunk
     * at the end is what tells it the turn is over. Closing the socket alone would do that too, but
     * only as a side effect of the connection dying - a client cannot tell that from a network fault,
     * and will treat a clean end of turn as a dropped connection.
     */
    private class ChunkedStream(
        private val out: OutputStream,
    ) {
        fun write(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            out.write(bytes.size.toString(16).toByteArray(CHARSET))
            out.write(CRLF)
            out.write(bytes)
            out.write(CRLF)
            out.flush()
        }

        fun finish() {
            out.write("0".toByteArray(CHARSET))
            out.write(CRLF)
            out.write(CRLF)
            out.flush()
        }

        private companion object {
            val CRLF = "\r\n".toByteArray(CHARSET)
        }
    }

    /** Compares in constant time so a wrong token cannot be recovered one character at a time. */
    private fun isAuthorized(header: String?): Boolean {
        val presented = header?.takeIf { it.startsWith(BEARER, ignoreCase = true) }?.substring(BEARER.length).orEmpty()
        return java.security.MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))
    }

    private fun readBody(request: HttpRequest): String? {
        val length = request.headers["content-length"]?.toIntOrNull() ?: return null
        if (length < 0 || length > MAX_BODY_BYTES) return null
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = request.stream.read(buffer, read, length - read)
            if (count < 0) break
            read += count
        }
        return String(buffer, 0, read, CHARSET)
    }

    private fun writeJson(
        out: OutputStream,
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(CHARSET)
        writeStatus(out, status, "application/json", bytes.size.toString())
        out.write(bytes)
        out.flush()
    }

    private fun writeStatus(
        out: OutputStream,
        status: Int,
        contentType: String,
        contentLength: String? = null,
        chunked: Boolean = false,
    ) {
        val headers =
            buildString {
                append("HTTP/1.1 $status ${reason(status)}\r\n")
                append("Content-Type: $contentType\r\n")
                // A streamed response has no length to declare; chunked carries the framing instead.
                // Everything else is measured so the connection can be closed cleanly instead of hanging.
                if (chunked) {
                    append("Transfer-Encoding: chunked\r\n")
                } else if (contentLength != null) {
                    append("Content-Length: $contentLength\r\n")
                }
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n\r\n")
            }
        out.write(headers.toByteArray(CHARSET))
        out.flush()
    }

    private fun errorBody(message: String): String = """{"type":"error","error":{"type":"router_error","message":${quote(message)}}}"""

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun reason(status: Int): String =
        when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            502 -> "Bad Gateway"
            else -> "Error"
        }

    /** One parsed request. The body is a reader because a streamed body must not be buffered. */
    class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val stream: InputStream,
    )

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val BACKLOG = 16
        const val READ_TIMEOUT_MILLIS = 30_000
        const val BEARER = "bearer "
        const val MESSAGES_PATH = "/v1/messages"
        const val COUNT_TOKENS_PATH = "/v1/messages/count_tokens"
        const val MODELS_PATH = "/v1/models"

        const val STOP_EVENT = "message_stop"
        const val MAX_BODY_BYTES = 8 * 1024 * 1024
        const val MAX_HEADER_LINE_BYTES = 8 * 1024

        /**
         * How long [stop] waits for workers to notice their closed sockets.
         *
         * Stopping is called from the service's `onDestroy`, so it runs on the main thread. This is a
         * ceiling on the wait, not the wait itself: connections are closed first, so this normally
         * returns immediately.
         */
        const val SHUTDOWN_GRACE_MILLIS = 250L

        /** Transient accept failures tolerated before the listener is declared dead. */
        const val MAX_ACCEPT_FAILURES = 5

        /** Pause between accept retries. Short: these failures are descriptor exhaustion, not backoff-worthy. */
        const val ACCEPT_RETRY_MILLIS = 200L

        /** How many times a loopback bind is retried when the port is still in TIME_WAIT. */
        const val BIND_ATTEMPTS = 5

        /** Pause between bind retries. Short: loopback bind recovery is quick. */
        const val BIND_RETRY_MILLIS = 50L

        val CHARSET = Charsets.UTF_8
        val DONE_EVENT = """{"type":"message_stop"}"""
    }
}

/**
 * What the server needs from the routing engine, expressed as a seam the tests can fake.
 *
 * Phase 4 replaces the implementations here with a real chain and a circuit breaker; until then the
 * server has no opinion about how a request is routed, only about how it is served.
 */
interface RouterGateway {
    /** Whether [rawRequest] asked for a streamed response. */
    fun isStreaming(rawRequest: String): Boolean

    /** A non-streamed answer in Anthropic's shape, or null when no provider in the chain served it. */
    fun handle(rawRequest: String): String?

    /**
     * A streamed answer, one translated Anthropic event at a time.
     *
     * The callback is the commit point: once the first event has been written to the client, the
     * chain can no longer fail over.
     */
    fun stream(
        rawRequest: String,
        onEvent: (String) -> Unit,
    )

    /** The model list, in Anthropic's shape, for `/v1/models`. */
    fun listModels(): String

    /**
     * A token count for `/v1/messages/count_tokens`.
     *
     * An estimate, because Claude Code only needs a figure to size its context window and the real
     * number is provider-specific - computing it exactly would cost a network call per invocation.
     * It lives on the gateway rather than in the server so a later phase can make it exact without
     * the server changing.
     */
    fun countTokens(rawRequest: String): String = """{"input_tokens":${rawRequest.length / 4}}"""

    /** An Anthropic-shaped error event, so a mid-stream failure is still something the client parses. */
    fun errorEvent(failure: Exception): String

    /** Reported when the listener itself fails, which is the one thing the server cannot recover. */
    fun onServerFailure(failure: Exception)
}

/**
 * A fresh bearer token for one server run.
 *
 * 32 random bytes per session, so nothing about the port or the key store has any bearing on whether
 * a caller can reach the router: knowing one server's token tells an attacker nothing about the next
 * one's. Top-level rather than on the server, because the service that issues it is not the server
 * that checks it.
 */
internal fun newSessionToken(): String {
    val bytes = ByteArray(32)
    java.security.SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}
