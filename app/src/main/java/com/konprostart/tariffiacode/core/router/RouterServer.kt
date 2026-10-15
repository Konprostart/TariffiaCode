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
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
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
        synchronized(lifecycleLock) {
            check(!running.get()) { "Router is already running" }
            // Nothing has been published and `running` is still false, so a bind failure needs no
            // undo - it propagates to the caller with the object exactly as it was.
            val server = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
            val executor = workerPool()
            // Everything a concurrent stop() needs is published before `running` flips. Publishing
            // the flag first is what let a stop() complete against a half-built server and strand
            // the listener.
            socket = server
            port = server.localPort
            acceptExecutor = executor
            running.set(true)
            Thread({ accept(server, executor) }, "router-accept").apply {
                isDaemon = true
                start()
            }
            return port
        }
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
            val tracked =
                synchronized(lifecycleLock) {
                    // The cap is on connections being served, not sockets being accepted: one over
                    // the cap is closed without ever being read, so it costs a descriptor for the
                    // length of a handshake rather than a worker for the length of a turn.
                    running.get() && clients.size < MAX_CONCURRENT_CLIENTS && clients.add(client)
                }
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
        val heartbeat = Heartbeat(stream)
        heartbeat.start()
        try {
            var sawStop = false
            gateway.stream(raw) { event ->
                if (event.contains(STOP_EVENT)) sawStop = true
                stream.write(sseFrames(event))
            }
            // Only if the chain did not already close its own stream: two message_stop events make a
            // client that is counting events, or keying off the terminal one, behave oddly.
            if (!sawStop) {
                stream.write(sseFrames(DONE_EVENT))
            }
            heartbeat.stop()
            stream.finish()
        } catch (failure: IOException) {
            // The agent closed the connection mid-turn. Nothing to report to a caller that left.
            heartbeat.stop()
        } catch (failure: Exception) {
            // Report it in the shape the client parses rather than dropping the stream silently.
            runCatching {
                heartbeat.stop()
                stream.write(sseFrames(gateway.errorEvent(failure)))
                stream.finish()
            }
        }
    }

    /**
     * One or more SSE frames: the event name, and the event itself.
     *
     * Both lines matter. The gateway hands over a complete Anthropic event object, and sending it as
     * the event *name* - which is what this used to do - left the client with a name and no payload,
     * so every frame parsed as empty and the stream was unwatchable.
     *
     * The name is taken from the payload rather than asked for separately, so the two cannot drift.
     * A payload with no recognisable type still goes out as data, because losing the payload is worse
     * than losing the name: a client that keys off `type` can recover, one left with no data cannot.
     *
     * A payload can legitimately hold *several* events: an OpenAI-shaped chunk that carries text and
     * a tool call together reaches here as two adjacent JSON objects. They used to be written as one
     * `data:` line, which is not a JSON document at all - the parse above failed, the name went
     * missing, and the client was handed `data: {..}{..}`. A strict client drops the stream there,
     * which is a connection lost partway through an answer that was being produced correctly.
     * So a payload that is not a single object is split and each object becomes its own frame.
     */
    private fun sseFrames(payload: String): ByteArray {
        val objects = splitTopLevelObjects(payload)
        // One object is the ordinary case, and it still carries its own name - taking the name only
        // on the split path is what left a single event with a payload and no `event:` line at all.
        if (objects.isEmpty()) return frame(payload, eventName = eventNameOf(payload))
        val out = java.io.ByteArrayOutputStream()
        objects.forEach { object_ -> out.write(frame(object_, eventName = eventNameOf(object_))) }
        return out.toByteArray()
    }

    /** A single event, with the name read out of its own `type`. */
    private fun frame(
        payload: String,
        eventName: String?,
    ): ByteArray {
        val head = if (eventName.isNullOrBlank()) "" else "event: $eventName\n"
        return "${head}data: $payload\n\n".toByteArray(CHARSET)
    }

    private fun eventNameOf(payload: String): String? =
        runCatching { json.parseToJsonElement(payload).jsonObject["type"]?.jsonPrimitive?.content }.getOrNull()

    /**
     * The adjacent top-level JSON objects in [payload], or an empty list if it is not several.
     *
     * Brace counting with string and escape awareness, so a `}` inside a string value - which a
     * model's own prose produces routinely - does not end an object early. Falls back to "not a
     * list" for anything that is a single object, which is the overwhelmingly common case and must
     * keep its exact bytes.
     */
    private fun splitTopLevelObjects(payload: String): List<String> {
        val trimmed = payload.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return emptyList()
        // Deliberately not "does this parse as one object". It does: the lenient parser reads the
        // first object and silently ignores whatever follows it, which is precisely how two glued
        // events used to get a name from the first and a payload containing both. The decision is
        // made by counting, not by asking a parser that is happy to stop early.
        val objects = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var inString = false
        var escaped = false
        for (character in trimmed) {
            current.append(character)
            when {
                escaped -> escaped = false
                character == '\\' && inString -> escaped = true
                character == '"' -> inString = !inString
                inString -> Unit
                character == '{' -> depth++
                character == '}' -> {
                    depth--
                    if (depth == 0) {
                        objects += current.toString()
                        current.setLength(0)
                    }
                }
            }
        }
        // Only a clean split counts. A truncated tail means the payload is one broken object, and
        // handing the client fragments of it is worse than handing it the whole thing.
        return if (objects.size > 1 && current.isEmpty()) objects else emptyList()
    }

    /**
     * The pool connections are served on, and why it is not a cached pool.
     *
     * A cached pool grows a thread per connection with nothing above it, so the bound on what a
     * caller can do to this process was set by the caller's own politeness. Each worker holds a
     * socket and, once the request is read, up to [MAX_BODY_BYTES] of buffer - so a client that
     * opens connections and sends a large body to each of them is a memory claim, not a request.
     *
     * Bounded, with a queue, because the accept loop is not allowed to block: a connection that
     * cannot be handed to a worker is closed in [accept] the same way any other hand-off failure is,
     * which costs that one caller nothing and leaves every established turn untouched.
     */
    private fun workerPool(): java.util.concurrent.ExecutorService =
        java.util.concurrent.ThreadPoolExecutor(
            WORKER_CORE_THREADS,
            WORKER_MAX_THREADS,
            WORKER_IDLE_SECONDS,
            TimeUnit.SECONDS,
            // A hand-off queue, not a waiting queue. With a deep queue the pool never grows past
            // its core threads - the connections sit in the queue instead - so a handful of idle
            // connections is enough to park every real request behind them until the read timeout
            // expires. Handing off means "a thread now or not at all", which is also the truth here:
            // work that is not being done right now should be refused while it is still cheap to
            // refuse, not queued behind work that will never finish.
            java.util.concurrent.SynchronousQueue(),
            ThreadFactory { runnable ->
                Thread(runnable, "router-conn-${connectionIds.incrementAndGet()}").apply {
                    isDaemon = true
                }
            },
        )

    /**
     * A comment line on the stream while the provider is still thinking.
     *
     * The gap this covers is the whole reasoning phase: the router sends the status line, then waits
     * for the first real event, and for a model that thinks for a while that wait is silent. A client
     * watching a stream has its own idea of how long silence is acceptable, and closing the turn
     * mid-thought is indistinguishable to it from a dropped connection - the symptom is a stream
     * that dies just as the answer is about to start, which is exactly where it is observed.
     *
     * `:` is the SSE comment. It is defined to be ignored by a conforming parser, so this costs the
     * client nothing, and it is written through the same lock as a real frame so it cannot land in
     * the middle of a chunk's bytes.
     *
     * Only fires when the stream has been quiet, so a chatty provider is never interleaved with.
     */
    private class Heartbeat(
        private val stream: ChunkedStream,
    ) {
        private val finished = java.util.concurrent.atomic.AtomicBoolean(false)

        fun start() {
            val thread =
                Thread(
                    {
                        while (!finished.get()) {
                            try {
                                Thread.sleep(HEARTBEAT_INTERVAL_MILLIS)
                            } catch (_: InterruptedException) {
                                return@Thread
                            }
                            // runCatching: a client that has already gone away makes this a write to
                            // a closed socket, which is not a reason to touch the caller's thread.
                            runCatching { stream.comment() }
                        }
                    },
                    "router-heartbeat",
                )
            thread.isDaemon = true
            thread.start()
        }

        fun stop() {
            finished.set(true)
        }
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
        /**
         * Serialises every write to the socket.
         *
         * The heartbeat writes from its own thread while a turn is in flight, and two threads
         * interleaving on one socket is not a rare race but a certainty: a chunk's length prefix
         * arriving after its payload turns the stream into garbage that the client drops mid-answer.
         */
        private val lock = Any()

        fun write(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            synchronized(lock) { frame(bytes) }
        }

        /** An SSE comment, skipped when the stream has moved recently. */
        fun comment() {
            val bytes = COMMENT.toByteArray(CHARSET)
            synchronized(lock) {
                if (System.nanoTime() - lastWriteNanos < QUIET_BEFORE_COMMENT_NANOS) return
                lastWriteNanos = System.nanoTime()
                frame(bytes)
            }
        }

        fun finish() {
            synchronized(lock) {
                out.write("0".toByteArray(CHARSET))
                out.write(CRLF)
                out.write(CRLF)
                out.flush()
            }
        }

        private var lastWriteNanos = System.nanoTime()

        private fun frame(bytes: ByteArray) {
            out.write(bytes.size.toString(16).toByteArray(CHARSET))
            out.write(CRLF)
            out.write(bytes)
            out.write(CRLF)
            // Flushed per frame, always. This is the whole point: a token that is written but not
            // flushed sits in the socket buffer, and a client watching a stream sees nothing until
            // the buffer happens to fill or the turn ends.
            out.flush()
            lastWriteNanos = System.nanoTime()
        }

        private companion object {
            val CRLF = "\r\n".toByteArray(CHARSET)
            val COMMENT = ": keep-alive\n\n"
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
                    append("Connection: keep-alive\r\n")
                    append("Cache-Control: no-cache\r\n")
                    append("X-Accel-Buffering: no\r\n")
                } else {
                    if (contentLength != null) {
                        append("Content-Length: $contentLength\r\n")
                    }
                    append("Cache-Control: no-store\r\n")
                    append("Connection: close\r\n")
                }
                append("\r\n")
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

        /**
         * The read timeout on an accepted socket.
         *
         * Back to thirty seconds, and deliberately not the several minutes an answer can take.
         * This bounds the *request*, not the response: once the body is read the router only writes,
         * and a streamed turn is never bounded by it. What it does bound is how long a connection
         * that sends nothing keeps a worker alive - which is exactly what a large value here trades
         * for, since a worker is a thread and a socket. The thinking phase is handled by the
         * heartbeat, which is the mechanism for it, not by holding sockets open longer.
         */
        const val READ_TIMEOUT_MILLIS = 30_000

        /** Workers started before any rejection is possible. */
        const val WORKER_CORE_THREADS = 4

        /** The ceiling on workers, whatever the caller does. */
        const val WORKER_MAX_THREADS = 16

        /** Idle seconds before an extra worker is returned. */
        const val WORKER_IDLE_SECONDS = 30L

        /**
         * Connections served at once. Past this a new one is closed unread.
         *
         * Deliberately below [WORKER_MAX_THREADS]. A client that opens connections and says nothing
         * occupies a worker for the whole read timeout, so a cap at or above the pool ceiling lets
         * such a client fill the pool and leave a legitimate request queued behind it until the
         * timeout expires - a working router refusing real work under a flood of nothing. Eight is
         * several times what a serial agent needs and leaves the pool headroom by construction.
         */
        const val MAX_CONCURRENT_CLIENTS = 8
        const val BEARER = "bearer "
        const val MESSAGES_PATH = "/v1/messages"
        const val COUNT_TOKENS_PATH = "/v1/messages/count_tokens"
        const val MODELS_PATH = "/v1/models"

        const val STOP_EVENT = "message_stop"
        const val MAX_BODY_BYTES = 8 * 1024 * 1024
        const val MAX_HEADER_LINE_BYTES = 8 * 1024

        /** How often the stream is checked for silence while a provider is thinking. */
        const val HEARTBEAT_INTERVAL_MILLIS = 10_000L

        /** How long the stream must be quiet before a keep-alive comment is worth writing. */
        const val QUIET_BEFORE_COMMENT_NANOS = 5_000L * 1_000_000L

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
