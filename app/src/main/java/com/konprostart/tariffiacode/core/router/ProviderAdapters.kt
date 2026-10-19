package com.konprostart.tariffiacode.core.router

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Ollama, which is OpenAI-compatible in wire shape and not in reachability.
 *
 * The whole reason this is a separate adapter rather than a flag is the endpoint: Ollama runs inside
 * the guest, which is the only place with a network, so the router reaches it over plain HTTP on a
 * loopback port rather than HTTPS. Keeping that in its own adapter means the guest-only assumption
 * is stated in exactly one place instead of being a conditional inside the shared one.
 *
 * It is also the fallback that makes the router worth having: it needs no key, so it is the last
 * entry of a chain and the only one that survives a user who has configured nothing.
 */
class OllamaAdapter(
    private val guestPort: Int = DEFAULT_GUEST_PORT,
) : ProviderAdapter {
    override val kind: RouterProviderKind = RouterProviderKind.LOCAL

    private val openAi = OpenAiCompatibleAdapter(PROVIDER_NAME)

    override fun buildRequest(
        request: AnthropicRequest,
        model: RouterModel,
        rawBody: String?,
    ): TranslatedRequest {
        // The body is OpenAI's; only the address differs.
        val translated = openAi.buildRequest(request, model)
        return translated.copy(path = "/$OPENAI_PATH")
    }

    override fun parseResponse(
        statusCode: Int,
        body: String,
        requestedModel: String,
    ): AnthropicResponse? = openAi.parseResponse(statusCode, body, requestedModel)

    override fun parseStreamEvent(raw: String): String? = openAi.parseStreamEvent(raw)

    /** The loopback URL Ollama is reached on, from the router's point of view. */
    fun baseUrl(): String = "http://127.0.0.1:$guestPort"

    companion object {
        const val DEFAULT_GUEST_PORT = 11434
        const val OPENAI_PATH = "v1/chat/completions"

        /** Named in the failure so a log line says which provider refused, not just that one did. */
        const val PROVIDER_NAME = "ollama"
    }
}

/**
 * Google's generative-language API.
 *
 * Different enough to be its own adapter in three places that each cost a turn if missed: the
 * content is an array of typed parts rather than one string, the candidate carries the text and the
 * tool call interleaved, and the endpoint carries the model in the path rather than the body.
 */
class GeminiAdapter(
    private val apiKey: String,
) : ProviderAdapter {
    override val kind: RouterProviderKind = RouterProviderKind.GEMINI

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /**
     * The key travels as a header, never as a query parameter.
     *
     * Google accepts both, and the query parameter is the older spelling that every URL in the path
     * was carrying: it ended up in the request line, and therefore in anything that ever printed,
     * parsed or re-serialised the URL - a redirect target, a retry, an exception message surfaced to
     * the client as an error event. A key in a header has no path to travel.
     *
     * This is the whole of the redaction question for this provider, and it is settled by not
     * putting the key where a URL can reach.
     */
    override fun extraHeaders(): Map<String, String> = mapOf("x-goog-api-key" to apiKey)

    override fun buildRequest(
        request: AnthropicRequest,
        model: RouterModel,
        rawBody: String?,
    ): TranslatedRequest {
        val contents =
            buildJsonArray {
                request.messages.forEach { message ->
                    add(
                        buildJsonObject {
                            put("role", if (message.role == "assistant") "model" else "user")
                            put(
                                "parts",
                                buildJsonArray {
                                    add(buildJsonObject { put("text", message.content?.toString() ?: "") })
                                },
                            )
                        },
                    )
                }
            }
        val body =
            buildJsonObject {
                put("contents", contents)
                request.system?.let {
                    put(
                        "systemInstruction",
                        buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", it.toString()) }) }) },
                    )
                }
                put(
                    "generationConfig",
                    buildJsonObject {
                        request.maxTokens?.let { put("maxOutputTokens", it) }
                        request.temperature?.let { put("temperature", it) }
                        request.topP?.let { put("topP", it) }
                    },
                )
                if (request.tools.isNotEmpty()) {
                    put(
                        "tools",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put(
                                        "functionDeclarations",
                                        buildJsonArray {
                                            request.tools.forEach { tool ->
                                                add(
                                                    buildJsonObject {
                                                        put("name", tool.name)
                                                        tool.description?.let { put("description", it) }
                                                        tool.inputSchema?.let { put("parameters", it) }
                                                    },
                                                )
                                            }
                                        },
                                    )
                                },
                            )
                        },
                    )
                }
            }
        // The method follows the request, and it has to.
        //
        // Google has two verbs and they do not answer the same thing: `generateContent` returns one
        // JSON object, `streamGenerateContent` returns a stream - a JSON array by default, and SSE
        // only when asked for it. Sending a non-streaming turn to the streaming verb produces `data:`
        // lines where a JSON object is expected, so the answer cannot be parsed, the turn is failed
        // over, and the caller sees an empty response with no indication that a provider answered.
        //
        // Picking the streaming verb unconditionally is exactly that: every short, non-streamed
        // Gemini request failed, while a streamed one looked fine, which reads as the router losing
        // text rather than as a wrong URL.
        val method = if (request.stream) "streamGenerateContent?alt=sse" else "generateContent"
        return TranslatedRequest(
            // The model travels in the path; the key does not - it is in extraHeaders(), where no
            // URL can reach it.
            path = "/v1beta/models/${model.model}:$method",
            body = body.toString(),
            stream = request.stream,
        )
    }

    override fun parseResponse(
        statusCode: Int,
        body: String,
        requestedModel: String,
    ): AnthropicResponse? {
        if (statusCode !in 200..299) return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        // A quota refusal served with a 200 is still a refusal, and reading it as an answer is how a
        // failed provider becomes an empty turn nobody can trace.
        providerReportedError(root)?.let { throw ProviderErrorFrame(PROVIDER_NAME, it.errorType, it.detail) }
        val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray ?: return null
        val content =
            buildList {
                parts.forEach { part ->
                    val obj = part.jsonObject
                    obj["text"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { add(AnthropicContent(type = "text", text = it)) }
                    obj["functionCall"]?.jsonObject?.let { call ->
                        add(
                            AnthropicContent(
                                type = "tool_use",
                                name = call["name"]?.jsonPrimitive?.content,
                                input = call["args"] ?: kotlinx.serialization.json.JsonObject(emptyMap()),
                            ),
                        )
                    }
                }
            }
        val usage = root["usageMetadata"]?.jsonObject
        return AnthropicResponse(
            model = requestedModel,
            content = content,
            stopReason = candidate["finishReason"]?.jsonPrimitive?.content.toGeminiStopReason(),
            usage =
                usage?.let {
                    AnthropicUsage(
                        inputTokens = it["promptTokenCount"]?.jsonPrimitive?.intOrNull() ?: 0,
                        outputTokens = it["candidatesTokenCount"]?.jsonPrimitive?.intOrNull() ?: 0,
                    )
                },
        )
    }

    override fun parseStreamEvent(raw: String): String? {
        val payload = raw.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return null
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        // Google's quota and overload refusals arrive as an in-stream `{"error": ...}` object. It
        // must fail the provider rather than pass as a frame with nothing in it.
        providerReportedError(root)?.let { throw ProviderErrorFrame(PROVIDER_NAME, it.errorType, it.detail) }
        val parts =
            root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get(
                "content",
            )?.jsonObject?.get("parts")?.jsonArray ?: return null
        val text =
            parts
                .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
                .joinToString("")
                .ifEmpty { return null }
        return buildJsonObject {
            put("type", "content_block_delta")
            put("index", 0)
            put(
                "delta",
                buildJsonObject {
                    put("type", "text_delta")
                    put("text", text)
                },
            )
        }.toString()
    }

    private fun String?.toGeminiStopReason(): String =
        when (this) {
            "MAX_TOKENS" -> "max_tokens"
            "STOP" -> "end_turn"
            else -> "end_turn"
        }

    private fun JsonPrimitive.intOrNull(): Int? = runCatching { content.toInt() }.getOrNull()

    private companion object {
        /** Named in the failure so a log line says which provider refused, not just that one did. */
        const val PROVIDER_NAME = "gemini"
    }
}

/** The adapters the router can use, resolved from the routing table's provider name. */
object ProviderAdapters {
    /**
     * Picks an adapter for [provider] by name, falling back to [RouterProviderKind.of].
     *
     * A name that matches no adapter yields null rather than a default: the router skips that chain
     * entry, which is exactly the behaviour wanted for a provider whose protocol the app has not
     * been taught yet. Guessing OpenAI-compatible for an unknown name would send a Gemini request
     * to a gateway that would answer with something the translator misreads.
     */
    fun forProvider(
        provider: String,
        keys: Map<String, String>,
        ollamaPort: Int = OllamaAdapter.DEFAULT_GUEST_PORT,
        /**
         * Provider names this user has given an endpoint of their own.
         *
         * The last branch of the `when`, and the reason adding a provider is not a release: a name
         * the user addressed themselves is spoken to with the OpenAI Chat Completions shape, which
         * is the shape that gateways of every kind actually implement. A name that is neither
         * built in nor configured still yields null, so an unaddressable entry in a hand-edited
         * table is skipped and reported rather than guessed at.
         */
        customProviders: Set<String> = emptySet(),
    ): ProviderAdapter? {
        val key = keys[provider]
        return when {
            provider.equals("ollama", true) || provider.equals("lmstudio", true) -> OllamaAdapter(ollamaPort)
            provider.equals("gemini", true) || provider.equals("google", true) -> key?.let { GeminiAdapter(it) }
            provider.equals("anthropic", true) -> key?.let { AnthropicAdapter(it) }
            // The shared shape carries the provider's own name, so a failure says which vendor
            // refused rather than naming the protocol it speaks.
            RouterProviderKind.of(provider) != null -> OpenAiCompatibleAdapter(provider.trim().lowercase())
            provider.trim().lowercase() in customProviders -> OpenAiCompatibleAdapter(provider.trim().lowercase())
            else -> null
        }
    }
}
