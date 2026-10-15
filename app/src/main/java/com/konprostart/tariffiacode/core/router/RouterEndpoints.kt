package com.konprostart.tariffiacode.core.router

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Where a provider the router has never heard of lives.
 *
 * The point of this store is that the router's vocabulary is not the same thing as the list of
 * providers it can reach. `defaultEndpoint` answers "where does *this known vendor* live", and a
 * `when` over a fixed set of names is the wrong shape for a user who has an OpenAI-compatible
 * gateway, a self-hosted vLLM, or a provider that did not exist when the app was built.
 *
 * So the rule is deliberately open: a name with a base URL saved here is a provider, whatever it is
 * called, and it is spoken to with the OpenAI Chat Completions shape, which is the shape every one
 * of those gateways implements. A name with no URL here is still filtered out of the chain exactly
 * as before - nothing is guessed, and a provider that cannot be addressed cannot be called.
 *
 * Encrypted for the same reason [RouterKeys] is: a base URL is a place a bearer token is about to be
 * sent, and a provider on a private network names itself in the process list otherwise.
 *
 * The URL is validated on the way in rather than on the way out. A chain walk happens per request
 * and a malformed URL there is a failed turn with a confusing message; rejecting it here means the
 * user is told at the moment they typed it.
 */
class RouterEndpoints(
    context: Context,
) {
    private val preferences: SharedPreferences by lazy { createPreferences(context) }

    /** The base URL saved for [provider], or null when it has none or an unusable one. */
    fun baseUrlFor(provider: String): String? {
        val stored = preferences.getString(keyName(provider), null) ?: return null
        return stored.trim().trimEnd('/').takeIf { isUsable(it) }
    }

    /** Every provider with a saved URL, keyed by the name as it appears in the routing table. */
    fun all(): Map<String, String> =
        preferences.all
            .mapNotNull { (name, value) ->
                val provider = name.removePrefix(KEY_PREFIX)
                val url = (value as? String)?.trim()?.trimEnd('/')
                if (provider.isEmpty() || url.isNullOrEmpty()) null else provider to url
            }.toMap()

    /**
     * Saves [baseUrl] for [provider], or removes the entry when it is blank.
     *
     * Returns the reason it was refused, or null when it was saved - a caller in the UI needs to
     * know *why* nothing happened, and throwing across a screen boundary loses that.
     */
    fun setBaseUrl(
        provider: String,
        baseUrl: String,
    ): String? {
        val name = provider.trim()
        if (name.isEmpty()) return "A provider needs a name"
        val cleaned = baseUrl.trim().trimEnd('/')
        if (cleaned.isEmpty()) {
            preferences.edit().remove(keyName(name)).apply()
            return null
        }
        if (!isUsable(cleaned)) return "That is not an http(s) URL with a host: $cleaned"
        preferences.edit().putString(keyName(name), cleaned).apply()
        return null
    }

    fun remove(provider: String) {
        preferences.edit().remove(keyName(provider.trim())).apply()
    }

    /**
     * Whether [url] can be dialled.
     *
     * Scheme and host only. No path is required, because a base URL legitimately may end in one -
     * OpenRouter's is `/api` - and the adapter appends its own path to whatever this is.
     *
     * Cleartext is allowed deliberately: the guest's own runtimes are reached over http on
     * loopback, and a user running something on their own network is the same case. The router
     * never binds anything on the strength of this value, so it cannot be used to reach *into* the
     * device; it only decides which address the router dials.
     */
    private fun isUsable(url: String): Boolean {
        val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = url.substringAfter("://").substringBefore('/')
        if (host.isBlank()) return false
        return !url.any { it.isWhitespace() }
    }

    private fun keyName(provider: String): String = "$KEY_PREFIX${provider.trim().lowercase()}"

    private fun createPreferences(context: Context): SharedPreferences {
        val masterKey =
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    companion object {
        private const val PREFS_NAME = "router_provider_endpoints"
        private const val KEY_PREFIX = "router_endpoint_"

        /** Kept so tests can assert the store never shares a file with the key store. */
        const val STORE_FILE_NAME: String = PREFS_NAME

        /** On-disk location, for diagnostics only. */
        fun storePath(context: Context): File = File(context.filesDir, "shared_prefs/$PREFS_NAME.xml")
    }
}
