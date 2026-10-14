package com.konprostart.tariffiacode.core.router

import android.content.Context
import java.io.File

/**
 * Reads the routing table, from wherever it lives.
 *
 * Reading and validating are kept apart - this knows where files are, [RouterConfigTable] knows what
 * a valid chain is - and choosing between them is [RouterConfigSource]'s job, so the rule can be
 * tested without a device.
 */
class RouterConfigLoader(
    private val context: Context,
    private val assetName: String = DEFAULT_ASSET_NAME,
) {
    /**
     * The table, where it came from, and why an override was not used.
     *
     * A missing or malformed asset yields an empty table and a populated rejection list rather than an
     * exception: the failure mode of a router that cannot be configured should be "no routing", not
     * "the app will not start".
     */
    fun load(): RouterConfigSource.Loaded {
        val userFile = userTableFile()
        return RouterConfigSource.resolve(
            readUserTable = { userFile?.takeIf { it.isFile }?.readText() },
            readBundledTable = { context.assets.open(assetName).bufferedReader().use { it.readText() } },
        )
    }

    /** Where a user's editable table would be, whether or not one is there. */
    fun userTableFile(): File? = RouterConfigSource.userTableFile(context.getExternalFilesDir(null), assetName)

    companion object {
        const val DEFAULT_ASSET_NAME = "claude_tiers.json"
    }
}
