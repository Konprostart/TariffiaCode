package com.konprostart.tariffiacode.core.router

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/**
 * A provider reported a failure inside a successful HTTP response.
 *
 * Some vendors answer with `200 OK` and put the failure in the payload: Anthropic opens the stream
 * and then sends an `{"type":"error", ...}` frame when it runs out of credit or is overloaded
 * mid-turn, and several OpenAI-shaped gateways answer a quota refusal with `200` and an `{"error":
 * ...}` body. The transport cannot see any of that - the status said 200 and the socket stayed open
 * - so it is only knowable by reading the payload.
 *
 * This is an [IOException] on purpose: the chain walk already treats a failure before the first byte
 * as "this provider gave the user nothing, try the next one", and that is exactly the truth here.
 * Anything else would report a refused provider as a turn that half-worked, and - worse - let the
 * error frame itself count as the first byte, which commits the chain and ends failover for good.
 */
internal class ProviderErrorFrame(
    val provider: String,
    val errorType: String?,
    val detail: String?,
) : IOException(ProviderReportedError(errorType, detail).describe(provider))

/**
 * A provider's own error, in whichever of the shapes the vendors send.
 *
 * Three are recognised because three vendors send one: Anthropic's `{"type":"error","error":
 * {...}}`, the OpenAI-shaped `{"error": {...}}` / `{"error":"..."}`, and Gemini's `{"error":{"code":
 * N,"message":"...","status":"..."}}`. A payload that is none of those is not an error, and yields
 * null so the caller carries on parsing it as the answer it claims to be.
 */
internal data class ProviderReportedError(
    val errorType: String?,
    val detail: String?,
) {
    /** The form used in a log line and an error event: what failed, and what the vendor said. */
    fun describe(provider: String): String =
        buildString {
            append(provider).append(" reported an error: ").append(errorType ?: "unknown")
            detail?.let { append(": ").append(it) }
        }
}

/**
 * Reads the error out of a provider frame or body, or null when the payload is not an error.
 *
 * Both fields are truncated rather than passed whole: they end up in a log line and in an error event
 * on the client, and an upstream error body can be arbitrarily long. Nothing here is a secret - a
 * provider's error payload does not carry the caller's key - but the length is still bounded so one
 * verbose upstream cannot flood either.
 *
 * Both of the ways to be wrong here are bad, and one of them is far worse: missing a refusal loses a
 * failover, but *inventing* one fails every healthy turn of a gateway that pads its frames with an
 * empty `error` member. So an envelope only counts when it carries something - an explicit
 * `{"type":"error"}` frame counts on its own, and everything else has to be a non-empty object or a
 * non-blank string.
 */
internal fun providerReportedError(payload: JsonObject?): ProviderReportedError? {
    val root = payload ?: return null
    val declaredType = root["type"]?.asString()
    val envelope = root["error"]?.takeUnless { it is JsonNull }
    if (envelope == null) {
        // Anthropic's shape names the failure in `type` and may carry no error object at all.
        return declaredType?.takeIf { it == ERROR_TYPE }?.let { ProviderReportedError(it, null) }
    }
    val error = envelope.asObject()
    // `type` is the clearest name a vendor gives it (Anthropic's "rate_limit_error"), `status` is
    // Gemini's own word for the condition, and `code` is the last resort - Gemini's is a number,
    // OpenRouter's a string, and either is worse to read than the two above it. Both fields are
    // truncated: a vendor that puts a novel in its `code` must not be able to flood a log line.
    val type =
        (
            error?.get("type")?.asString()
                ?: error?.get("status")?.asString()
                ?: error?.get("code")?.asString()
                ?: declaredType?.takeIf { it == ERROR_TYPE }
                ?: envelope.asString()
        )?.take(MAX_DETAIL_CHARS)
    val detail =
        (
            error?.get("message")?.asString()
                ?: error?.get("status")?.asString()
                ?: root["message"]?.asString()
                ?: envelope.asString()
        )?.take(MAX_DETAIL_CHARS)
    // An envelope that says nothing is not a refusal. `{"error":{}}` and `{"error":""}` are what a
    // gateway emits when it has nothing to report, and reading them as failures would empty the
    // chain on a provider that is answering perfectly well.
    if (type.isNullOrBlank() && detail.isNullOrBlank()) return null
    return ProviderReportedError(type, detail)
}

private const val ERROR_TYPE = "error"
private const val MAX_DETAIL_CHARS = 300

private fun JsonElement.asObject(): JsonObject? = runCatching { jsonObject }.getOrNull()

private fun JsonElement.asString(): String? = runCatching { jsonPrimitive.content }.getOrNull()
