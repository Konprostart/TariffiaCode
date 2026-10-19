package com.konprostart.tariffiacode.core.router

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        val body = JsonObject(fields + ("model" to JsonPrimitive(model.model)))
        return TranslatedRequest(path = MESSAGES_PATH, body = body.toString(), stream = request.stream)
    }

    /** The answer is already Anthropic's, so it is read rather than rebuilt. */
    override fun parseResponse(
        statusCode: Int,
        body: String,
        requestedModel: String,
    ): AnthropicResponse? {
        if (statusCode !in 200..299) return null
        val decoded = runCatching { json.decodeFromString<AnthropicResponse>(body) }.getOrNull()
        // A real message decodes as one, and only one parse is spent on the path every turn takes.
        if (decoded != null && decoded.type != ERROR_TYPE) return decoded
        // Either the body is not a message or it says it is an error: an error body served with a 200
        // is still a refusal - the shape Anthropic uses when the credit balance is gone. Raised rather
        // than returned as null so the reason reaches the log line and the client; null would fail over
        // just as correctly but would report "unreadable".
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (root != null) providerReportedError(root)?.let { throw ProviderErrorFrame(PROVIDER_NAME, it.errorType, it.detail) }
        return decoded ?: root?.let {
            AnthropicResponse(
                model = it["model"]?.jsonPrimitive?.content ?: requestedModel,
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
     *
     * Except when the frame is Anthropic's own error. Anthropic answers `200` and then reports
     * running out of credit, or being overloaded mid-turn, as an in-stream `{"type":"error"}` frame.
     * Forwarding it would be the worst of both worlds: the chain would count it as the first byte
     * (committing the turn and ending failover for good), the client would be handed an error instead
     * of an answer, and the provider would be recorded as having served the request. Raising it
     * instead is what turns "Anthropic is out of credit" into "the next provider in the chain is
     * tried" - which is the entire reason the chain exists.
     */
    override fun parseStreamEvent(raw: String): String? {
        val payload = raw.removePrefix(DATA_PREFIX).trim()
        if (payload.isEmpty() || payload == DONE_MARKER) return null
        val frame = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        providerReportedError(frame)?.let { throw ProviderErrorFrame(PROVIDER_NAME, it.errorType, it.detail) }
        return frame.toString()
    }

    private companion object {
        const val MESSAGES_PATH = "/v1/messages"
        const val DATA_PREFIX = "data:"
        const val DONE_MARKER = "[DONE]"
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val ERROR_TYPE = "error"

        /** Named in the failure so a log line says which provider refused, not just that one did. */
        const val PROVIDER_NAME = "anthropic"
    }
}
