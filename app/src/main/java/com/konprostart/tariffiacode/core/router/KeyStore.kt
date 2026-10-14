package com.konprostart.tariffiacode.core.router

/**
 * Where the router keeps provider keys.
 *
 * This exists because the router had no way to be exercised outside the app: the only implementation
 * needed an Android `Context` and an encrypted `SharedPreferences`, so the one part of the router that
 * touches a user's credentials was also the part that could never be run against anything. An
 * interface is what lets the same routing code run against a real encrypted store on a device and
 * against a plain map in a harness, which is the only way to point a real client at it at all.
 *
 * Implementations must be safe to read repeatedly, and [snapshot] must be consistent across the whole
 * of one routing pass - a rotation that re-read the store between attempts could change the chain
 * underneath an in-flight turn.
 */
interface KeyStore {
    /** Whether [provider] has a usable key. A blank entry counts as absent, never as empty. */
    fun hasKey(provider: String): Boolean

    /** The key for [provider], or null when there is none. Null is the normal answer for most providers. */
    fun keyFor(provider: String): String?

    /** Stores [key] for [provider]. A blank key removes the entry rather than storing an empty one. */
    fun setKey(
        provider: String,
        key: String,
    )

    /** Forgets [provider]'s key. */
    fun removeKey(provider: String)

    /** The providers that currently have a key, in no particular order. */
    fun configuredProviders(): Set<String>

    /**
     * Every configured key, resolved once per routing pass.
     *
     * Snapshotting is what keeps a turn stable: a provider disabled mid-turn must not change the
     * chain an in-flight request is walking.
     */
    fun snapshot(): Map<String, String>

    /** Forgets every key. */
    fun clear()
}

/**
 * Keys held in memory, for a process that has no encrypted store to offer.
 *
 * Deliberately not a production store: it keeps keys in plain memory and in plain text, and exists so
 * the router can be run somewhere other than a phone. Nothing should hold one of these on a device.
 */
class MapKeyStore(
    initial: Map<String, String> = emptyMap(),
) : KeyStore {
    private val keys = LinkedHashMap(initial)

    override fun hasKey(provider: String): Boolean = keys.containsKey(provider)

    override fun keyFor(provider: String): String? = keys[provider]?.takeIf { it.isNotBlank() }

    override fun setKey(
        provider: String,
        key: String,
    ) {
        if (key.isBlank()) keys.remove(provider) else keys[provider] = key
    }

    override fun removeKey(provider: String) {
        keys.remove(provider)
    }

    override fun configuredProviders(): Set<String> = keys.keys.toSet()

    override fun snapshot(): Map<String, String> = keys.filterValues { it.isNotBlank() }

    override fun clear() = keys.clear()
}
