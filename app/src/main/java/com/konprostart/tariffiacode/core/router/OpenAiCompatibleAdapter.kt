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
 *  - **reasoning arrives in its own field** (`reasoning_content`, or `reasoning` on OpenRouter and
 *    Groq) and has no equivalent in Anthropic's shape. It is relayed as a thinking block, and - when
 *    a model spends its whole answer there - surfaced as the answer text, because a turn the client
 *    sees as empty is a turn it can only retry forever.
 *
 * The streamed answer is rebuilt as a complete Anthropic event sequence - `message_start`, a
 * `content_block_start` per block, deltas, block stops, `message_delta`, `message_stop` - rather than
 * a bare sequence of deltas. A client that is owed a message object it never receives builds an
 * assistant turn with no content in it, whatever the deltas said, and reports that turn as having
 * produced no visible output.
 */
class OpenAiCompatibleAdapter : ProviderAdapter {
    override val kind: RouterProviderKind = RouterProviderKind.OPENAI_COMPATIBLE

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /**
     * Per-turn stream state.
     *
     * One adapter is built per attempt, so these describe exactly one turn and nothing has to be
     * reset between them. They exist because the Anthropic event sequence is a sequence, and because
     * its blocks are typed: text, thinking and tool_use each need their own `content_block_start`,
     * carrying their own fields, before any delta may land in them. A `tool_use` block that is never
     * opened is a tool call the client cannot act on - it has no id and no name - and an agent turn
     * that consists only of those renders as nothing at all.
     */
    private var turnOpened = false

    /** Whether an output block is open. */
    private var blockOpen = false

    /** The model name the client asked for, reported by `message_start`. */
    private var requestedModel: String = "router"

    /** Index of the block currently open, and what kind it is; null when none is. */
    private var openIndex = -1

    /** What kind of block is open, which is what a delta may legally land in. */
    private var openType: String? = null

    /** The next block index, handed out in order so every block in the turn has its own. */
    private var nextIndex = 0

    /** Tool calls already opened, by the provider's own call index, so each is opened exactly once. */
    private val openedToolCalls = mutableSetOf<Int>()

    /** Reasoning seen this turn, so a turn that ends with nothing else still has an answer to give. */
    private val reasoning = StringBuilder()

    /**
     * Whether anything the client can act on has been sent - text or a tool call.
     *
     * A thinking block is not that: the client collapses it, renders nothing, and still ends up
     * asking for a turn that produced no visible output. So this is what decides whether the
     * reasoning has to be promoted into the answer at the end.
     */
    private var producedAnswer = false

    /** Whether the turn has already been closed, so a late frame cannot close it twice. */
    private var turnFinished = false

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
        // The adapter instance is created per turn, so this is the only place the model name is
        // known and it is what message_start has to report: the client asked for a name, and the
        // provider's own name is a property of the chain entry rather than of the request.
        requestedModel = request.model
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
        val usage = root["usage"]?.jsonObject
        val reasoning =
            (message["reasoning_content"] ?: message["reasoning"])
                ?.jsonPrimitive
                ?.contentOrNullSafe()
                ?.takeIf { it.isNotBlank() }
        val blocks = buildList {
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
            // A reasoning-only model answered in the field with no Anthropic equivalent. Surfacing it
            // as the answer is the difference between a turn the client can read and one it can only
            // retry: an empty content list is not an answer to anything.
            if (none { it.type == "text" || it.type == "tool_use" }) {
                reasoning?.let { add(AnthropicContent(type = "text", text = it)) }
            }
        }
        return AnthropicResponse(
            model = requestedModel,
            content = blocks,
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
        if (payload.isEmpty()) return null
        // `[DONE]` is the other way a turn ends, and a provider that only ever sends it has answered
        // with nothing at all - which is the shape the client sees as a turn with no visible output.
        if (payload == "[DONE]") return closeTurn(null).ifEmpty { null }
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val delta = choice.get("delta")?.jsonObject
        val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNullSafe()?.takeIf { it != "null" }

        // The turn opens here, on the provider's first frame, and not before: a provider that turns
        // out to answer with nothing gets no frames at all rather than an empty turn.
        //
        // These two frames are the whole reason this method is not a pure function. A Claude client
        // creates its turn on `message_start` and its output block on `content_block_start`, and a
        // delta arriving with neither is a delta with nowhere to land - the text arrives, the client
        // has no turn to hold it, and the answer renders as nothing. Emitting only deltas was not a
        // shortcut but a broken sequence, and it broke every provider that speaks this shape:
        // DeepSeek, OpenRouter, Groq and Ollama. Anthropic-as-provider is untouched because its own
        // frames already carry the full sequence and are passed straight through.
        val builder = StringBuilder()
        if (!turnOpened) {
            turnOpened = true
            builder.append(messageStart(requestedModel))
        }

        // Reasoning, in whichever of the two fields the vendor chose. Anthropic has no equivalent of
        // it in the streaming shape, so it becomes a thinking block: something the client renders,
        // rather than bytes thrown away for a turn that may end up with nothing else in it.
        val reasoningText = (delta?.get("reasoning_content") ?: delta?.get("reasoning"))
            ?.jsonPrimitive
            ?.contentOrNullSafe()
        if (!reasoningText.isNullOrEmpty()) {
            if (reasoning.length < MAX_REASONING_CHARS) reasoning.append(reasoningText)
            if (openIndex < 0) builder.append(openThinkingBlock())
            if (isOpen("thinking")) {
                builder.append(
                    deltaEvent("thinking_delta") { put("thinking", reasoningText) },
                )
            }
        }

        val text = delta?.get("content")?.jsonPrimitive?.contentOrNullSafe()
        if (!text.isNullOrBlank()) {
            if (!isOpen("text")) builder.append(openTextBlock())
            builder.append(deltaEvent("text_delta") { put("text", text) })
        }

        // A tool call is announced by its own block, with the id and name the client needs to act on
        // it, and only its arguments stream. Sending the arguments alone - which is what this used to
        // do, at index 0 inside a text block - produces a turn the client cannot render and cannot
        // execute, which for an agent is the same as no answer at all.
        delta?.get("tool_calls")?.jsonArray?.forEach { call ->
            val function = call.jsonObject["function"]?.jsonObject
            val callIndex = call.jsonObject["index"]?.jsonPrimitive?.intOrNull() ?: 0
            if (openedToolCalls.add(callIndex)) {
                builder.append(
                    openToolBlock(
                        id = call.jsonObject["id"]?.jsonPrimitive?.contentOrNullSafe() ?: "toolu_$callIndex",
                        name = function?.get("name")?.jsonPrimitive?.contentOrNullSafe() ?: "tool",
                    ),
                )
            }
            function?.get("arguments")?.jsonPrimitive?.contentOrNullSafe()?.takeIf { it.isNotBlank() }?.let { args ->
                builder.append(deltaEvent("input_json_delta") { put("partial_json", args) })
            }
        }

        // `finish_reason` is how the client learns why the turn ended. A tool call ended on
        // `tool_calls` means the agent is expected to run something, not that the answer is finished.
        if (finish != null) builder.append(closeTurn(finish))
        return builder.toString().ifEmpty { null }
    }

    /**
     * Ends the turn: the open block is closed, the reason is sent, and a turn that produced nothing
     * visible still produces something.
     *
     * The last part is the whole reason reasoning is kept. A reasoning model can spend an entire
     * answer in the field Anthropic has no name for; relayed as a thinking block, that leaves the
     * client with a turn it cannot show and will only retry. So when the turn ends having produced
     * neither text nor a tool call, the reasoning is closed out as the answer text. It is the model's
     * own answer - only its label was missing.
     */
    private fun closeTurn(finishReason: String?): String {
        if (turnFinished) return ""
        turnFinished = true
        val builder = StringBuilder()
        if (!producedAnswer) {
            // Promote the reasoning into the answer, or say plainly that there was none. Either way
            // the client ends this turn with something in it; a turn it cannot render is a turn it
            // retries, and a model that only ever reasons would otherwise never finish at all.
            val answer = reasoning.toString().ifBlank { NO_ANSWER_TEXT }
            builder.append(openTextBlock())
            builder.append(deltaEvent("text_delta") { put("text", answer) })
        }
        if (openIndex >= 0) builder.append(closeOpenBlock())
        builder.append(messageDelta(finishReason?.toAnthropicStopReason() ?: "end_turn"))
        return builder.toString()
    }

    private fun isOpen(type: String): Boolean = blockOpen && openType == type

    private fun openTextBlock(): String = openBlock("text") { put("text", "") }

    private fun openThinkingBlock(): String = openBlock("thinking") { put("thinking", "") }

    private fun openToolBlock(
        id: String,
        name: String,
    ): String =
        openBlock("tool_use") {
            put("id", id)
            put("name", name)
            put("input", buildJsonObject { })
        }

    /**
     * Opens the next block, closing whatever was open before it.
     *
     * The block's own `type` is written here rather than by the caller because it is the field the
     * client dispatches on: a `content_block_start` whose payload does not say what kind of block
     * it opens is a block no client can interpret, and the delta that follows lands nowhere - which
     * the client reports as a turn with no visible output.
     */
    private fun openBlock(
        type: String,
        fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): String {
        val closing = if (openIndex >= 0) closeOpenBlock() else ""
        openIndex = nextIndex++
        openType = type
        blockOpen = true
        if (type != "thinking") producedAnswer = true
        return closing +
            buildJsonObject {
                put("type", "content_block_start")
                put("index", openIndex)
                put("content_block", buildJsonObject { put("type", type); fields() })
            }.toString()
    }

    private fun closeOpenBlock(): String {
        blockOpen = false
        openType = null
        return buildJsonObject {
            put("type", "content_block_stop")
            put("index", openIndex)
        }.toString()
    }

    /** A delta for the block currently open, wherever that block sits in the turn. */
    private fun deltaEvent(
        deltaType: String,
        fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): String =
        buildJsonObject {
            put("type", "content_block_delta")
            put("index", openIndex)
            put("delta", buildJsonObject { put("type", deltaType); fields() })
        }.toString()

    private fun messageStart(model: String): String =
        buildJsonObject {
            put("type", "message_start")
            put(
                "message",
                buildJsonObject {
                    put("id", "msg_router")
                    put("type", "message")
                    put("role", "assistant")
                    put("model", model)
                    put("content", buildJsonArray { })
                    put("stop_reason", kotlinx.serialization.json.JsonNull)
                    put("stop_sequence", kotlinx.serialization.json.JsonNull)
                    put(
                        "usage",
                        buildJsonObject {
                            put("input_tokens", 0)
                            put("output_tokens", 0)
                        },
                    )
                },
            )
        }.toString()

    private fun messageDelta(stopReason: String): String =
        buildJsonObject {
            put("type", "message_delta")
            put(
                "delta",
                buildJsonObject {
                    put("stop_reason", stopReason)
                    put("stop_sequence", kotlinx.serialization.json.JsonNull)
                },
            )
            put("usage", buildJsonObject { put("output_tokens", 0) })
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

        /**
         * How much reasoning is kept for the fallback answer.
         *
         * A reasoning-only turn has to say something, and saying the whole chain of thought is not
         * the answer to a coding question - but a fallback that is a fragment reads as a truncated
         * one. A few thousand characters is the model's own summary of what it was doing.
         */
        const val MAX_REASONING_CHARS = 4_000

        /**
         * What a turn says when the provider answered with nothing at all.
         *
         * Not an apology and not a retry: a visible line saying so is the difference between the
         * user seeing a broken provider and the user seeing a blank screen.
         */
        const val NO_ANSWER_TEXT = "[router] The provider ended this turn without any answer text."
    }
}
