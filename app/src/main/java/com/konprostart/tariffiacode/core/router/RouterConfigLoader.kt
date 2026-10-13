package com.konprostart.tariffiacode.core.router

import android.content.Context

/**
 * Reads the routing table out of the app's assets.
 *
 * The table ships as an asset because it changes far more often than the app does, and a chain that
 * names a model which no longer exists would otherwise surface as a provider outage: the chain would
 * burn its fallbacks on a model that can never answer, and the real cause would stay invisible.
 *
 * Reading and validating are kept apart - this knows about assets, [RouterConfigTable] knows about
 * what a valid chain is - so the table can be loaded somewhere that has no asset bundle at all.
 */
class RouterConfigLoader(
    private val context: Context,
    private val assetName: String = DEFAULT_ASSET_NAME,
) {
    /**
     * The table, plus what was rejected.
     *
     * A missing or malformed asset yields an empty table and a populated [Result.rejected] rather
     * than an exception: the router's failure mode when it cannot be configured should be "no
     * routing", not "the app will not start".
     */
    fun load(): RouterConfigTable.Result =
        runCatching { context.assets.open(assetName).bufferedReader().use { it.readText() } }
            .fold(
                onSuccess = { RouterConfigTable.fromJson(it) },
                onFailure = {
                    RouterConfigTable.Result(
                        RouterConfig(),
                        listOf(it.message ?: it::class.simpleName.orEmpty()),
                    )
                },
            )

    companion object {
        const val DEFAULT_ASSET_NAME = "claude_tiers.json"
    }
}
