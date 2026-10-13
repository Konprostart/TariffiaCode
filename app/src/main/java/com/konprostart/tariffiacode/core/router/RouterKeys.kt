package com.konprostart.tariffiacode.core.router

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Where the router keeps the API keys it rotates through.
 *
 * Separate from the app's existing provider catalogue, on purpose and in both directions:
 *
 *  - it does not read or write that catalogue, so enabling the router cannot change what the local
 *    OpenCode agent does;
 *  - a key entered here is used by the router and by nothing else, so a user can hand the router a
 *    third-party key without that key reaching any agent;
 *  - it does not touch VPS or SSH agents, which carry their own keys in their own configuration on
 *    the machine they run on.
 *
 * Backed by its own encrypted preferences file and its own master key name, so removing the router
 * removes exactly its keys and nothing else.
 *
 * Nothing here is ever logged, and [keysFor] is the only way out: callers get a key, they do not get
 * the store.
 */
class RouterKeys(
    context: Context,
) : KeyStore {
    private val preferences: SharedPreferences by lazy { createPreferences(context) }

    /** Whether [provider] has a usable key. A blank entry is stored as absent, never as empty. */
    override fun hasKey(provider: String): Boolean = preferences.contains(keyName(provider))

    /**
     * The key for [provider], or null when the user has not configured one.
     *
     * Null is the normal answer for most providers: a chain entry whose provider has no key is
     * skipped rather than tried and failed, which is the whole reason this returns null instead of
     * an empty string.
     */
    override fun keyFor(provider: String): String? = preferences.getString(keyName(provider), null)?.takeIf { it.isNotBlank() }

    /** Stores [key] for [provider]. A blank key removes the entry rather than storing an empty one. */
    override fun setKey(
        provider: String,
        key: String,
    ) {
        val name = keyName(provider)
        if (key.isBlank()) {
            preferences.edit().remove(name).apply()
        } else {
            preferences.edit().putString(name, key).apply()
        }
    }

    override fun removeKey(provider: String) {
        preferences.edit().remove(keyName(provider)).apply()
    }

    /** The providers that currently have a key, in no particular order. */
    override fun configuredProviders(): Set<String> = preferences.all.keys.mapNotNull { providerForKey(it) }.toSet()

    /**
     * Every configured key, resolved once per routing pass.
     *
     * A rotation must not read the key store between attempts: a provider disabled mid-turn would
     * otherwise change the chain underneath an in-flight request. Snapshotting once per pass is what
     * keeps a turn stable.
     */
    override fun snapshot(): Map<String, String> =
        configuredProviders().mapNotNull {
                provider ->
            keyFor(provider)?.let { provider to it }
        }.toMap()

    /** Forgets every key. Used when the router is switched off, so nothing is left behind. */
    override fun clear() {
        preferences.edit().clear().apply()
    }

    /**
     * Whether the encrypted store could be opened.
     *
     * The key material can be unreadable - a restore onto a different device, a changed keystore -
     * and [EncryptedSharedPreferences] throws when it cannot. Failing to open must disable the
     * router rather than take the app down with it, so callers ask first and treat false as "no keys
     * configured" until the user sets one again.
     */
    fun isAvailable(): Boolean =
        runCatching {
            preferences.all
        }.isSuccess

    private fun keyName(provider: String): String = "$KEY_PREFIX$provider"

    private fun providerForKey(key: String): String? = key.removePrefix(KEY_PREFIX).takeIf { it.isNotEmpty() }

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
        private const val PREFS_NAME = "router_provider_keys"
        private const val KEY_PREFIX = "router_key_"

        /** Kept so tests can assert the store never shares a file with anything else. */
        const val STORE_FILE_NAME: String = PREFS_NAME

        /** The on-disk location, for diagnostics only. Never contains key material itself. */
        fun storePath(context: Context): File = File(context.filesDir, "shared_prefs/$PREFS_NAME.xml")
    }
}
