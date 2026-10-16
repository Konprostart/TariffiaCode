package com.konprostart.tariffiacode.core.router

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Where a provider lives and how it is authenticated. */
data class ProviderEndpoint(
    val baseUrl: String,
    val apiKey: String? = null,
)

/**
 * Walks a tier's chain until something serves the request, and stops the moment that would be wrong.
 *
 * ## The first-byte rule
 *
 * Failover is possible in exactly one direction, and the direction is decided by one fact: whether
 * anything has already reached the client.
 *
 * **Before the first byte**, nothing is committed. A 429, a 403, a 5xx, a refused connection or a
 * stream that dies before producing a single event all mean the provider gave the user nothing, so
 * the next provider in the chain can be tried with no one downstream aware it happened. This is where
 * rotation earns its keep.
 *
 * **After the first byte**, it is committed. The client has already parsed a response and possibly
 * rendered part of an answer. Swapping providers now would either duplicate content or break a
 * sequence it is midway through, so the failure is reported in the shape the client parses and the
 * turn ends - honestly, rather than as a stream that quietly stops.
 *
 * The boundary is not something a caller has to remember to respect. It is derived from whether the
 * relay below was invoked, which is the only way to know whether bytes actually left.
 *
 * ## Timeouts
 *
 * 8 seconds to connect, 25 seconds to read. The read timeout is per read, not a budget for the whole
 * answer: a model that takes a minute to think has not failed, it has started, and cutting it off
 * would fail over a perfectly healthy provider in the name of a faster error.
 *
 * @param endpointFor injected so tests can point the chain at a local server instead of the internet.
 */
class RouterEngine(
    private val keys: () -> Map<String, String>,
    private val config: () -> RouterConfig,
    /**
     * Base URLs for providers this build has never heard of, from [RouterEndpoints].
     *
     * Resolved per request rather than held, so a provider the user adds takes effect on the next
     * turn without restarting the service - the same rule the key store already follows.
     */
    private val customEndpoints: () -> Map<String, String> = { emptyMap() },
    private val breakers: CircuitBreaker = CircuitBreaker(),
    private val endpointFor: (String) -> ProviderEndpoint? = { provider -> defaultEndpoint(provider, keys()[provider]) },
    private val client: OkHttpClient = defaultClient(),
    /**
     * Where the turn's own story goes, one line per decision.
     *
     * A turn that has died has to leave a trace naming the hop: the provider, the attempt, and the
     * reason, or diagnosing "empty" stays a guessing game for as long as the server lives. Default
     * silent so the engine stays neutral in tests; the service wires in both logcat and a file.
     */
    private val log: (String) -> Unit = {},
) : RouterGateway {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    override fun isStreaming(rawRequest: String): Boolean =
        runCatching {
            json.parseToJsonElement(rawRequest).jsonObject["stream"]?.jsonPrimitive?.content == "true"
        }.getOrDefault(false)

    /**
     * Serves a non-streamed request, failing over freely.
     *
     * There is no commit point to respect here: the client receives one response or an error, so
     * every attempt is still before-the-first-byte in the only sense that matters.
     */
    override fun handle(rawRequest: String): String? {
        val request = decode(rawRequest) ?: return null
        val chain = usableChain(request.tier)
        log("turn start: tier=${request.tier} stream=false chain=${chain.joinToString(",") { it.model }}")
        for ((index, model) in chain.withIndex()) {
            val attempt = "${index + 1}/${chain.size}"
            val result = runCatching { answerOnce(request, model, rawRequest) }
            if (result.isSuccess) {
                breakers.recordSuccess(model.provider)
                return result.getOrNull()
            }
            log("attempt $attempt provider=${model.provider}: failed: ${result.exceptionOrNull()?.message}")
            breakers.recordFailure(model.provider)
        }
        log("turn failed: every provider in the chain failed")
        return null
    }

    /**
     * Serves a streamed request, and stops failing over the moment the first event reaches the client.
     *
     * A provider that answers 200 and then dies having produced nothing has still failed before the
     * first byte, and is failed over. One that emitted an event has not, and is not.
     */
    override fun stream(
        rawRequest: String,
        onEvent: (String) -> Unit,
    ) {
        val request = decode(rawRequest) ?: throw IOException("Unreadable request")
        var failure: IOException = IOException("No provider in the chain could serve this request")
        val committed = AtomicBoolean(false)
        val relay: (String) -> Unit = { event ->
            committed.set(true)
            onEvent(event)
        }

        val chainModels = usableChain(request.tier)
        log("turn start: tier=${request.tier} stream=true chain=${chainModels.joinToString(",") { it.model }}")
        for ((index, model) in chainModels.withIndex()) {
            val attempt = "${index + 1}/${chainModels.size}"
            // Resolved with the user's own endpoints, and identically in every place: an entry the
            // chain walk accepted but this call cannot resolve is a turn that fails as "no adapter"
            // on the provider the user just configured.
            val adapter =
                ProviderAdapters.forProvider(model.provider, keys(), customProviders = customEndpoints().keys)
                    ?: run {
                        log("attempt $attempt provider=${model.provider}: no adapter, skipping")
                        continue
                    }
            val translated =
                runCatching { adapter.buildRequest(request, model) }.getOrNull()
                    ?: run {
                        log("attempt $attempt provider=${model.provider}: untranslatable, skipping")
                        continue
                    }
            log("attempt $attempt provider=${model.provider} url=${call(translated, model, adapter).url}")
            try {
                streamOnce(translated, model, adapter, relay)
                log("attempt $attempt provider=${model.provider}: stream ended normally")
                breakers.recordSuccess(model.provider)
                return
            } catch (io: IOException) {
                if (committed.get()) {
                    log("attempt $attempt provider=${model.provider}: committed stream failed: ${io.message}")
                    throw io
                }
                failure = io
                log("attempt $attempt provider=${model.provider}: failed before first event: ${io.message}")
                breakers.recordFailure(model.provider)
            }
        }
        log("turn failed: ${failure.message}")
        throw failure
    }

    private fun answerOnce(
        request: AnthropicRequest,
        model: RouterModel,
        rawBody: String,
    ): String {
        val adapter =
            ProviderAdapters.forProvider(model.provider, keys(), customProviders = customEndpoints().keys)
                ?: throw IOException("No adapter")
        val translated = adapter.buildRequest(request, model, rawBody)
        client.newCall(call(translated, model, adapter)).execute().use { response ->
            if (!response.isSuccessful) throw ProviderRefused(response.code)
            val parsed =
                adapter.parseResponse(response.code, response.body?.string().orEmpty(), request.model)
                    ?: throw IOException("Unreadable answer from ${model.provider}")
            return json.encodeToString(parsed)
        }
    }

    private fun streamOnce(
        translated: TranslatedRequest,
        model: RouterModel,
        adapter: ProviderAdapter,
        relay: (String) -> Unit,
    ) {
        client.newCall(call(translated, model, adapter)).execute().use { response ->
            if (!response.isSuccessful) throw ProviderRefused(response.code)
            val body = response.body ?: throw IOException("Empty stream")
            // The idle timeout is not fixed for the life of the stream: a provider that accepts and
            // then says nothing gets sixty seconds, and once a byte has arrived the quiet budget
            // widens, because a model that thinks before speaking may legitimately be silent for
            // minutes. Under the old single 300-second idle timeout one silent provider stalled the
            // whole turn for five minutes, and a failing chain looked indistinguishable from slow.
            val source = body.source()
            source.timeout().timeout(FIRST_BYTE_DEADLINE_SECONDS, TimeUnit.SECONDS)
            val input = java.io.PushbackInputStream(source.inputStream(), 1)
            var firstByteSeen = false
            while (true) {
                val line = readSseLine(input) ?: break
                if (!firstByteSeen) {
                    firstByteSeen = true
                    source.timeout().timeout(STEADY_IDLE_SECONDS, TimeUnit.SECONDS)
                }
                if (line.startsWith(SSE_DATA)) {
                    adapter.parseStreamEvent(line.removePrefix(SSE_DATA).trim())?.let(relay)
                }
            }
        }
    }

    /**
     * One upstream SSE line, or null at end of stream.
     *
     * Byte at a time over a buffered source, rather than `BufferedReader.readLine()`. The reader
     * version holds a line back until it sees its terminator, and a provider that writes a frame in
     * pieces - or without the trailing newline a well-behaved stream always sends - leaves that line
     * sitting in the reader's 8 KB buffer. Nothing reaches the client until the buffer fills or the
     * turn ends, which is a stream that looks stalled exactly while it is being written. Reading
     * bytes as they arrive hands each completed line on immediately, and costs one array read per
     * line rather than per byte.
     *
     * Both terminators are accepted, because SSE permits `\n`, `\r\n` and a lone `\r`, and a
     * provider that uses the last one would otherwise yield one enormous line per stream.
     */
    private fun readSseLine(source: java.io.PushbackInputStream): String? {
        val line = java.io.ByteArrayOutputStream()
        while (true) {
            val byte = source.read()
            if (byte < 0) return if (line.size() == 0) null else String(line.toByteArray(), Charsets.UTF_8)
            when (byte.toChar()) {
                '\n' -> return String(line.toByteArray(), Charsets.UTF_8)
                '\r' -> {
                    // CR ends the line. In CRLF the LF is consumed with it; after a lone CR the
                    // byte that follows is already the next line's, so it is pushed back rather
                    // than swallowed - dropping it would silently corrupt the next frame.
                    val next = source.read()
                    if (next >= 0 && next.toChar() != '\n') source.unread(next)
                    return String(line.toByteArray(), Charsets.UTF_8)
                }
                else -> line.write(byte)
            }
            if (line.size() > MAX_UPSTREAM_LINE_BYTES) return String(line.toByteArray(), Charsets.UTF_8)
        }
    }

    private fun call(
        translated: TranslatedRequest,
        model: RouterModel,
        adapter: ProviderAdapter,
    ): Request {
        val endpoint = resolveEndpoint(model.provider) ?: throw IOException("No endpoint for ${model.provider}")
        return Request.Builder()
            .url("${endpoint.baseUrl}${translated.path}")
            .post(translated.body.toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                endpoint.apiKey?.let { header("Authorization", "Bearer $it") }
                // Some vendors do not authenticate with a bearer token, and the transport should not
                // need to know which is which.
                adapter.extraHeaders().forEach { (name, value) -> header(name, value) }
            }
            .build()
    }

    /**
     * The chain for [tier], minus what cannot be tried at all.
     *
     * Three things disqualify an entry, and all three are checked here so the chain that gets walked
     * is the chain that could actually work:
     *
     *  - no credential for it, save for a local entry, which is reachable without a key;
     *  - no adapter for the vendor, so nothing can speak to it;
     *  - nowhere to send it;
     *  - an open breaker, which is the whole reason the chain does not re-pay for a provider that is
     *    known to be down.
     */
    private fun usableChain(tier: RouterTier): List<RouterModel> {
        val available = keys()
        return config().chainFor(tier).filter { model ->
            hasCredential(model.provider, available) &&
                ProviderAdapters.forProvider(model.provider, available, customProviders = customEndpoints().keys) != null &&
                resolveEndpoint(model.provider) != null &&
                breakers.allows(model.provider)
        }
    }

    /**
     * Whether [provider] is one this user could actually authenticate to.
     *
     * Stated here rather than inferred from [endpointFor] returning null, because that is a property
     * of one particular endpoint table. A local entry is reachable without a key; everything else
     * needs one, and walking a chain into a provider that will answer 401 is the waste this whole
     * class exists to avoid.
     */
    private fun hasCredential(
        provider: String,
        available: Map<String, String>,
    ): Boolean = RouterProviderKind.of(provider) == RouterProviderKind.LOCAL || available.containsKey(provider)

    /**
     * Where [provider] is reached, or null when it cannot be.
     *
     * A saved custom URL wins over the built-in table, because that is the reason it can be saved:
     * the user pointed this name at that address. The API key comes from the key store under the
     * same name, so a custom provider is entered in one place and authenticated in the other, which
     * is what "endpoint and key" means everywhere else in this app.
     *
     * Reached by both the chain walk and the request builder deliberately. They were separate
     * lookups once, which is the shape where a provider can be deemed usable and then found
     * unaddressable on the turn that uses it.
     */
    private fun resolveEndpoint(provider: String): ProviderEndpoint? {
        customEndpoints()[provider.trim().lowercase()]?.let { return ProviderEndpoint(it) }
        return endpointFor(provider)
    }

    private fun decode(rawRequest: String): AnthropicRequest? =
        runCatching { json.decodeFromString<AnthropicRequest>(rawRequest) }.getOrNull()

    override fun listModels(): String {
        val tiers = RouterTier.entries
        val data =
            tiers.joinToString(",") { tier ->
                val slug = tier.name.lowercase()
                """{"id":"router-$slug","object":"model","owned_by":"router","tier":"$slug"}"""
            }
        val names = tiers.joinToString(",") { "\"${it.name.lowercase()}\"" }
        return """{"data":[$data],"tiers":[$names]}"""
    }

    override fun errorEvent(failure: Exception): String =
        """{"type":"error","error":{"type":"router_error","message":${quote(failure.message ?: "provider failed")}}}"""

    override fun onServerFailure(failure: Exception) = Unit

    private fun quote(value: String): String =
        buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(character)
                }
            }
            append('"')
        }

    /** A provider that refused. Distinguished from a transport failure so both can be failed over alike. */
    private class ProviderRefused(
        val status: Int,
    ) : IOException("Provider refused with HTTP $status")

    companion object {
        private const val SSE_DATA = "data:"

        /** An upstream frame larger than this is a provider problem, not a reason to hold the turn. */
        private const val MAX_UPSTREAM_LINE_BYTES = 16 * 1024 * 1024
        private const val OLLAMA_PORT = 11434
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        const val CONNECT_TIMEOUT_SECONDS = 15L

        /**
         * Two separate quiet budgets rather than one.
         *
         * [FIRST_BYTE_DEADLINE_SECONDS] bounds the silence before the first byte: a provider that
         * has accepted the request and gone quiet means a broken turn, because almost every
         * streaming provider emits something (a role frame, a usage object) far sooner than this.
         * Once a byte has arrived, [STEADY_IDLE_SECONDS] takes over as the gap between bytes, wide
         * enough for a model that thinks for minutes before it speaks again.
         */
        const val FIRST_BYTE_DEADLINE_SECONDS = 60L
        const val STEADY_IDLE_SECONDS = 300L

        /**
         * Where each vendor lives.
         *
         * Gemini carries its key in the path, so no bearer header is put on top of it.
         */
        fun defaultEndpoint(
            provider: String,
            key: String?,
        ): ProviderEndpoint? =
            when (provider.trim().lowercase()) {
                "ollama", "lmstudio" -> ProviderEndpoint("http://127.0.0.1:$OLLAMA_PORT")
                "gemini", "google" -> key?.let { ProviderEndpoint("https://generativelanguage.googleapis.com") }
                "anthropic" -> key?.let { ProviderEndpoint("https://api.anthropic.com", it) }
                "deepseek" -> key?.let { ProviderEndpoint("https://api.deepseek.com", it) }
                "groq" -> key?.let { ProviderEndpoint("https://api.groq.com", it) }
                "openrouter" -> key?.let { ProviderEndpoint("https://openrouter.ai/api", it) }
                else -> null
            }

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(STEADY_IDLE_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
    }
}
