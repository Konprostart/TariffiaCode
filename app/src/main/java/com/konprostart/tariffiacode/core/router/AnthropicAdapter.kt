package com.konprostart.tariffiacode.core.router

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Anthropic itself, as a provider in the chain.
 *
 * Every other adapter here translates - Anthropic's request shape in, someone else's out, and back
 * again. This one does not, because the request already arrived in Anthropic's own shape. There is
 * nothing to translate, and pretending otherwise would be the surest way to introduce a difference
 * that only shows up in production.
 *
 * Which leaves the model name, the one thing that does have to change. The incoming model is how the
 * client asked and the router has already used it to pick a tier; what the provider is called is a
 * property of the chain entry, and sending the client's name back would be sending the wrong one.
 */
class AnthropicAdapter(
    private val apiKey: String,
    private val anthropicVersion: String = ANTHROPIC_VERSION,
) : ProviderAdapter {
    override val kind: RouterProviderKind = RouterProviderKind.ANTHROPIC

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    override fun extraHeaders(): Map<String, String> =
        mapOf(
            // Anthropic takes the key in x-api-key, not Authorization, and refuses the request
            // outright without a version header. Neither belongs in the transport.
            "x-api-key" to apiKey,
            "anthropic-version" to anthropicVersion,
        )

    override fun buildRequest(
        request: AnthropicRequest,
        model: RouterModel,
        rawBody: String?,
    ): TranslatedRequest {
        // The original body with only the model replaced. Round-tripping through the parsed type
        // would drop whatever the client sent that we do not model - cache_control, thinking,
        // effort - and this provider understands all of it perfectly well already.
        val fields =
            runCatching { json.parseToJsonElement(rawBody ?: "{}").jsonObject }.getOrElse { JsonObject(emptyMap()) }
        val body = JsonObject(fields + ("model" to buildJsonObject { put("model", model.model) }))
        return TranslatedRequest(path = MESSAGES_PATH, body = body.toString(), stream = request.stream)
    }

    /** The answer is already Anthropic's, so it is read rather than rebuilt. */
    override fun parseResponse(
        statusCode: Int,
        body: String,
        requestedModel: String,
    ): AnthropicResponse? {
        if (statusCode !in 200..299) return null
        return runCatching { json.decodeFromString<AnthropicResponse>(body) }.getOrElse {
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            AnthropicResponse(
                model = root["model"]?.jsonPrimitive?.content ?: requestedModel,
                content = emptyList(),
            )
        }
    }

    /**
     * The frame is already an Anthropic event.
     *
     * Returned as the event object, because the server writes the event name and the data line
     * itself - for every adapter. Doing that here too is how the shape came out nested inside a delta
     * once already.
     */
    override fun parseStreamEvent(raw: String): String? {
        val payload = raw.removePrefix(DATA_PREFIX).trim()
        if (payload.isEmpty() || payload == DONE_MARKER) return null
        return runCatching { json.parseToJsonElement(payload).toString() }.getOrNull()
    }

    private companion object {
        const val MESSAGES_PATH = "/v1/messages"
        const val DATA_PREFIX = "data:"
        const val DONE_MARKER = "[DONE]"
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
