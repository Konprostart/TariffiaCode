package com.konprostart.tariffiacode.core.router

import android.content.Context
import kotlinx.serialization.json.Json

/**
 * Loads and validates the routing table.
 *
 * The table ships as an asset because it changes far more often than the app does, and a chain that
 * names a model which no longer exists would otherwise surface as a provider outage - the chain
 * would burn its fallbacks on a model that can never answer, and the real cause would stay invisible.
 *
 * So the loader is strict about what it will accept and reports what it dropped. A router that
 * quietly serves a subset is diagnosable; one that quietly serves nonsense is not.
 */
class RouterConfigLoader(
    private val context: Context,
    private val assetName: String = DEFAULT_ASSET_NAME,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    /**
     * The table, plus what was rejected.
     *
     * A malformed asset yields an empty table and a populated [rejected] rather than an exception:
     * the router's failure mode when it cannot be configured should be "no routing", not "the app
     * will not start".
     */
    fun load(): Result =
        runCatching {
            val text = context.assets.open(assetName).bufferedReader().use { it.readText() }
            val parsed = json.decodeFromString<RouterConfig>(text)
            validate(parsed)
        }.getOrElse { Result(emptyList(), listOf(it.message ?: it::class.simpleName.orEmpty())) }

    /**
     * Drops entries that could never serve a turn, and chains that are left empty.
     *
     * Both checks exist because the failure they prevent looks like something else: a model with too
     * small a context fails mid-turn, and an empty chain turns into a rate-limit symptom that sends
     * the circuit breaker chasing a provider that was never in the running.
     */
    fun validate(config: RouterConfig): Result {
        val rejected = mutableListOf<String>()
        val chains =
            config.chains.map { chain ->
                val models =
                    chain.models.filter { model ->
                        val usable = model.contextWindow == 0 || model.canCarryClaudePrompt
                        if (!usable) rejected += "${chain.tier}:${model.id} context ${model.contextWindow}"
                        usable
                    }
                chain.copy(models = models)
            }.filter { chain ->
                val kept = chain.models.isNotEmpty()
                if (!kept) rejected += "${chain.tier}: chain is empty"
                kept
            }
        return Result(chains, rejected)
    }

    /** A validated table, and what validation dropped. */
    data class Result(
        val config: RouterConfig,
        val rejected: List<String> = emptyList(),
    ) {
        val isUsable: Boolean get() = config.isUsable
    }

    companion object {
        const val DEFAULT_ASSET_NAME = "claude_tiers.json"
    }
}
