package com.konprostart.tariffiacode.core.router

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The Anthropic Messages API request, as Claude Code sends it.
 *
 * Only the fields the router actually has to understand are modelled. Anything else is carried
 * through untouched by the adapters - the router's job is to route, not to reinterpret a prompt,
 * and a model of the whole API surface would be one more thing to go stale when Anthropic adds a
 * field.
 */
@Serializable
data class AnthropicRequest(
    @SerialName("model") val model: String,
    @SerialName("messages") val messages: List<AnthropicMessage> = emptyList(),
    @SerialName("system") val system: JsonElement? = null,
    @SerialName("tools") val tools: List<AnthropicTool> = emptyList(),
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("temperature") val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("stop_sequences") val stopSequences: List<String>? = null,
    @SerialName("stream") val stream: Boolean = false,
) {
    /**
     * Which Claude tier this request is for.
     *
     * Null when the id names no known tier - an unknown future model, or a caller that bypassed the
     * picker. The router then has to guess, and guessing sonnet is the conservative choice: it is
     * the primary working tier, so a model it does not recognise gets a chain that can actually do
     * the work rather than a cheap one that cannot edit.
     */
    val tier: RouterTier get() = RouterTier.ofModelId(model) ?: RouterTier.SONNET
}

@Serializable
data class AnthropicMessage(
    @SerialName("role") val role: String,
    @SerialName("content") val content: JsonElement? = null,
)

@Serializable
data class AnthropicTool(
    @SerialName("name") val name: String,
    @SerialName("description") val description: String? = null,
    @SerialName("input_schema") val inputSchema: JsonElement? = null,
)
