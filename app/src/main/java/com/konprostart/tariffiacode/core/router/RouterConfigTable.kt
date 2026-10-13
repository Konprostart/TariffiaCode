package com.konprostart.tariffiacode.core.router

import kotlinx.serialization.json.Json

/**
 * Parses and validates the routing table, with no opinion about where the text came from.
 *
 * Split out from the asset reader so that loading a table does not require an Android `Context`. That
 * separation is what makes the table runnable against a real client off-device, which is the only way
 * to find out whether the table this ships produces requests anyone accepts - something no unit test
 * can answer, because every unit test would be asking the table a question we wrote ourselves.
 */
object RouterConfigTable {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    /**
     * The table, plus what was rejected.
     *
     * A malformed table yields an empty result and a populated [Result.rejected] rather than an
     * exception: the failure mode of a router that cannot be configured should be "no routing", not a
     * crash somewhere unrelated to the cause.
     */
    fun fromJson(text: String): Result =
        runCatching { validate(json.decodeFromString<RouterConfig>(text)) }
            .getOrElse { Result(RouterConfig(), listOf(it.message ?: it::class.simpleName.orEmpty())) }

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
        return Result(RouterConfig(chains), rejected)
    }

    /** A validated table, and what validation dropped. */
    data class Result(
        val config: RouterConfig,
        val rejected: List<String> = emptyList(),
    ) {
        val isUsable: Boolean get() = config.isUsable
    }
}
