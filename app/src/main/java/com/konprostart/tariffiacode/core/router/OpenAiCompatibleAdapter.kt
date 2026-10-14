package com.konprostart.tariffiacode.core.router

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Turns an Anthropic request into the shape one provider expects, and its answer back again.
 *
 * The reason this is an interface and not a class with a `when` is that a provider is *data* - a
 * line in `claude_tiers.json` - and the only thing that should require code is a new protocol. So
 * each protocol family gets one adapter, and everything above them (the server, the chain, the
 * circuit breaker) is written against this and never against a provider.
 *
 * Both directions have to be complete or the router is worse than useless: a translated request with
 * a mistranslated response will stream a tool call the agent cannot parse, and Claude Code would fail
 * mid-turn in a way that looks like a provider problem.
 */
interface ProviderAdapter {
    /** The protocol this adapter speaks, as named in the routing table. */
    val kind: RouterProviderKind

    /**
     * Headers this provider needs beyond the bearer token.
     *
     * Empty for the OpenAI-compatible shape, which authenticates with `Authorization: Bearer`. It
     * exists for the ones that do not: Anthropic takes the key in `x-api-key` and refuses the
     * request without a version header. Encoding that here keeps it out of the transport, which
     * should not have to know which vendor it is talking to.
     */
    fun extraHeaders(): Map<String, String> = emptyMap()

    /**
     * Builds the request body and the URL path for [model].
     *
     * [rawBody] is the request exactly as it arrived, for adapters that pass it through rather than
     * rebuild it. Null when the caller has no original to offer, which is the case a translating
     * adapter is indifferent to and a passing-through one is not.
     */
    fun buildRequest(
        request: AnthropicRequest,
        model: RouterModel,
        rawBody: String? = null,
    ): TranslatedRequest

    /**
     * The provider's non-streaming answer, in Anthropic's shape.
     *
     * Returns null when the provider failed rather than answered - a 429, a refused connection - so
     * the caller can move down the chain. Returning an error body instead would let a failure look
     * like a successful empty reply.
     */
    fun parseResponse(
        statusCode: Int,
        body: String,
        requestedModel: String,
    ): AnthropicResponse?

    /**
     * One event from a streamed answer, already in Anthropic's event shape.
     *
     * Null for a provider frame that carries nothing to forward - a keep-alive, a usage-only
     * trailer - which is different from a parse failure, and the caller needs the two apart: a
     * malformed frame should surface, a silent one should not.
     */
    fun parseStreamEvent(raw: String): String?
}

/** Where a translated request goes, and what it carries. */
data class TranslatedRequest(
    val path: String,
    val body: String,
    val stream: Boolean,
)

/** An Anthropic-shaped non-streaming answer. */
@Serializable
data class AnthropicResponse(
    @SerialName("id") val id: String = "msg_router",
    @SerialName("type") val type: String = "message",
    @SerialName("role") val role: String = "assistant",
    @SerialName("model") val model: String,
    @SerialName("content") val content: List<AnthropicContent>,
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("usage") val usage: AnthropicUsage? = null,
)

@Serializable
data class AnthropicContent(
    @SerialName("type") val type: String,
    @SerialName("text") val text: String? = null,
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("input") val input: kotlinx.serialization.json.JsonElement? = null,
)

@Serializable
data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
)

/**
 * The OpenAI Chat Completions adapter, which also serves most third-party gateways.
 *
 * Three differences from Anthropic's shape are worth naming, because each one is a way a turn fails
 * silently rather than loudly:
 *
 *  - **tool calls are a separate array**, not part of the message content, so a tool call has to be
 *    lifted out of the content stream and rebuilt;
 *  - **`system` is a message**, not a top-level field, so it has to be prepended;
 *  - **there is no thinking block**. Several models return reasoning inline; forwarding it as text
 *    would put the model's private reasoning into the answer the user sees, so it is dropped rather
 *    than surfaced.
 */
class OpenAiCompatibleAdapter : ProviderAdapter {
    override val kind: RouterProviderKind = RouterProviderKind.OPENAI_COMPATIBLE

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    override fun buildRequest(
        request: AnthropicRequest,
        model: RouterModel,
        rawBody: String?,
    ): TranslatedRequest {
        val messages =
            buildJsonArray {
                request.system?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { system ->
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", system.toString())
                        },
                    )
                }
                request.messages.forEach { message ->
                    add(
                        buildJsonObject {
                            put("role", message.role)
                            put("content", message.content?.toString() ?: "")
                        },
                    )
                }
            }
        val body =
            buildJsonObject {
                put("model", model.model)
                put("messages", messages)
                request.maxTokens?.let { put("max_tokens", it) }
                request.temperature?.let { put("temperature", it) }
                request.topP?.let { put("top_p", it) }
                request.stopSequences?.let { put("stop", it.toJsonArrayOfStrings()) }
                if (request.tools.isNotEmpty()) put("tools", request.tools.toOpenAiTools())
                put("stream", request.stream)
            }
        return TranslatedRequest(path = CHAT_COMPLETIONS_PATH, body = body.toString(), stream = request.stream)
    }

    override fun parseResponse(
        statusCode: Int,
        body: String,
        requestedModel: String,
    ): AnthropicResponse? {
        if (statusCode !in 200..299) return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val message = choice["message"]?.jsonObject ?: return null
        val content =
            buildList {
                message["content"]?.jsonPrimitive?.contentOrNullSafe()?.takeIf { it.isNotBlank() }?.let {
                    add(
                        AnthropicContent(type = "text", text = it),
                    )
                }
                message["tool_calls"]?.jsonArray?.forEach { call ->
                    val fn = call.jsonObject["function"]?.jsonObject
                    add(
                        AnthropicContent(
                            type = "tool_use",
                            id = call.jsonObject["id"]?.jsonPrimitive?.contentOrNullSafe(),
                            name = fn?.get("name")?.jsonPrimitive?.contentOrNullSafe(),
                            input =
                                fn?.get(
                                    "arguments",
                                )?.let { runCatching { json.parseToJsonElement(it.jsonPrimitive.content) }.getOrNull() },
                        ),
                    )
                }
            }
        val usage = root["usage"]?.jsonObject
        return AnthropicResponse(
            model = requestedModel,
            content = content,
            stopReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNullSafe()?.toAnthropicStopReason(),
            usage =
                usage?.let {
                    AnthropicUsage(
                        inputTokens = it["prompt_tokens"]?.jsonPrimitive?.intOrNull() ?: 0,
                        outputTokens = it["completion_tokens"]?.jsonPrimitive?.intOrNull() ?: 0,
                    )
                },
        )
    }

    override fun parseStreamEvent(raw: String): String? {
        val payload = raw.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return null
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val delta = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject ?: return null
        val builder = StringBuilder()
        delta["content"]?.jsonPrimitive?.contentOrNullSafe()?.takeIf {
            it.isNotBlank()
        }?.let {
            builder.append(
                event(
                    "content_block_delta",
                    buildJsonObject {
                        put("type", "text_delta")
                        put("text", it)
                    },
                ),
            )
        }
        delta["tool_calls"]?.jsonArray?.forEach { call ->
            val fn = call.jsonObject["function"]?.jsonObject
            fn?.get("arguments")?.jsonPrimitive?.contentOrNullSafe()?.takeIf { it.isNotBlank() }?.let {
                builder.append(
                    event(
                        "content_block_delta",
                        buildJsonObject {
                            put("type", "input_json_delta")
                            put("partial_json", it)
                        },
                    ),
                )
            }
        }
        return builder.toString().ifEmpty { null }
    }

    private fun event(
        type: String,
        delta: JsonObject,
    ): String =
        buildJsonObject {
            put("type", type)
            put("index", 0)
            put("delta", delta)
        }.toString()

    private fun List<AnthropicTool>.toOpenAiTools(): JsonArray =
        buildJsonArray {
            this@toOpenAiTools.forEach { tool ->
                add(
                    buildJsonObject {
                        put("type", "function")
                        put(
                            "function",
                            buildJsonObject {
                                put("name", tool.name)
                                tool.description?.let { put("description", it) }
                                tool.inputSchema?.let { put("parameters", it) }
                            },
                        )
                    },
                )
            }
        }

    private fun List<String>.toJsonArrayOfStrings(): JsonArray =
        buildJsonArray { this@toJsonArrayOfStrings.forEach { add(JsonPrimitive(it)) } }

    private fun JsonPrimitive.contentOrNullSafe(): String? = runCatching { content }.getOrNull()

    private fun JsonPrimitive.intOrNull(): Int? = runCatching { content.toInt() }.getOrNull()

    /** OpenAI's finish reasons are not Anthropic's, and a mismatch shows up as a wrong end-of-turn. */
    private fun String.toAnthropicStopReason(): String =
        when (this) {
            "stop" -> "end_turn"
            "length" -> "max_tokens"
            "tool_calls", "function_call" -> "tool_use"
            else -> "end_turn"
        }

    private companion object {
        const val CHAT_COMPLETIONS_PATH = "/v1/chat/completions"
    }
}
