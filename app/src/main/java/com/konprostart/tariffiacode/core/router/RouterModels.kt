package com.konprostart.tariffiacode.core.router

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The Claude model tier a request belongs to.
 *
 * Claude Code names its models by tier rather than by id, and expects the same models to keep
 * meaning the same thing within a turn: the tool that summarises a file and the tool that edits it
 * should not silently talk to different backends. A tier is that stability boundary, and the
 * router's job is to pick a concrete model for it without breaking the turn.
 */
enum class RouterTier {
    /** Fast and cheap. Read, search, summarise. */
    @SerialName("haiku")
    HAIKU,

    /** The primary working tier: coding, tool use. */
    @SerialName("sonnet")
    SONNET,

    /** The strongest available; rare, and the one most likely to be rate-limited. */
    @SerialName("opus")
    OPUS,
    ;

    companion object {
        /** Maps a Claude model id such as `claude-3-5-haiku-20241022` onto its tier. */
        fun ofModelId(modelId: String): RouterTier? {
            val id = modelId.lowercase()
            return when {
                "opus" in id -> OPUS
                "sonnet" in id -> SONNET
                "haiku" in id -> HAIKU
                "fable" in id -> SONNET
                else -> null
            }
        }
    }
}

/**
 * One entry in a tier's failover chain: a concrete model at a concrete provider.
 *
 * Deliberately not an enum. The point of the router is that these lists change every few weeks,
 * and they are shipped as data for exactly that reason - so a new provider is a JSON edit, not a
 * release. See `claude_tiers.json`.
 */
@Serializable
data class RouterModel(
    @SerialName("provider") val provider: String,
    @SerialName("model") val model: String,
    /**
     * Context window in tokens, used to reject an entry that cannot carry Claude's system prompt
     * and tool definitions at all.
     *
     * Below this a coding turn fails for reasons that look like provider outages, which would send
     * the failover chain chasing a model that can never work.
     */
    @SerialName("contextWindow") val contextWindow: Int = 0,
    /** Whether the model can be asked to call tools. A tier used for edits cannot skip this. */
    @SerialName("supportsTools") val supportsTools: Boolean = true,
) {
    /** Below this a turn cannot even carry Claude's own prompt, let alone the conversation. */
    val canCarryClaudePrompt: Boolean get() = contextWindow >= MINIMUM_USABLE_CONTEXT

    val id: String get() = "$provider/$model"

    companion object {
        /**
         * Claude Code's own prompt plus its tool definitions run to roughly 25k tokens, so anything
         * materially below 64k will fail on a normal turn rather than on an unusual one.
         */
        const val MINIMUM_USABLE_CONTEXT = 64_000
    }
}

/**
 * A tier and the models that may serve it, best first.
 *
 * The order is the failover order. It is data, so a provider that keeps failing can be demoted
 * without a release - though the circuit breaker handles that at runtime too.
 */
@Serializable
data class RouterTierChain(
    @SerialName("tier") val tier: RouterTier,
    @SerialName("models") val models: List<RouterModel> = emptyList(),
) {
    /** Chains with nothing in them are a configuration mistake, not a fallback to nowhere. */
    val isUsable: Boolean get() = models.isNotEmpty()
}

/**
 * The whole routing table: which models may serve which Claude tier.
 *
 * Every tier is expected to be present. A missing one is tolerated rather than fatal so that a
 * partially-edited asset degrades to "that tier has no fallback" instead of taking the router down.
 */
@Serializable
data class RouterConfig(
    @SerialName("chains") val chains: List<RouterTierChain> = emptyList(),
) {
    private val byTier: Map<RouterTier, RouterTierChain> = chains.associateBy { it.tier }

    fun chainFor(tier: RouterTier): List<RouterModel> = byTier[tier]?.models ?: emptyList()

    /**
     * The tiers that could not serve anything.
     *
     * Surfaced rather than swallowed: a router silently missing its opus chain would look like a
     * provider problem on every request, and the cost of finding that out would be paid in support.
     */
    val incompleteTiers: List<RouterTier> = RouterTier.entries.filterNot { byTier[it].isUsable }

    val isUsable: Boolean get() = incompleteTiers.isEmpty()
}

/**
 * The provider kinds the translator knows how to talk to.
 *
 * A closed set on purpose - every new kind means a new adapter and its tests - while the *providers
 * themselves* stay open, which is the distinction that matters: adding DeepSeek's third model is
 * data, adding a protocol is code.
 */
enum class RouterProviderKind {
    /** OpenAI Chat Completions shape: most third-party gateways. */
    OPENAI_COMPATIBLE,

    /** Google's generative-language shape, which differs in several small ways. */
    GEMINI,

    /** OpenAI-compatible but reached inside the guest's own network rather than the device's. */
    LOCAL,
    ;

    companion object {
        fun of(name: String): RouterProviderKind? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** How the router reaches a provider's API. */
enum class RouterEndpoint {
    /** Over HTTPS, from the app process. */
    HTTPS,

    /** Over HTTP to a loopback port inside the guest. */
    GUEST_LOOPBACK,
}
