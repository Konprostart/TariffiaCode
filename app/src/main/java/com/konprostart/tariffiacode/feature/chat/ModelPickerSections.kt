package com.konprostart.tariffiacode.feature.chat

import com.konprostart.tariffiacode.core.api.OpenCodeProvider
import com.konprostart.tariffiacode.runtime.LocalAgent

/**
 * Providers the server knows about but cannot run yet: its catalog minus the connected set.
 *
 * The picker lists these as "add a key" rows. A server with no credentials answers `/provider`
 * with its full catalog — 226 providers on OpenCode 1.19 — while `connected` stays empty, which
 * used to leave the picker with nothing to show and no way to act on it.
 */
internal fun unconfiguredProviders(
    all: List<OpenCodeProvider>,
    connectedIds: Set<String>,
): List<OpenCodeProvider> = all.filter { it.id !in connectedIds }.sortedBy { it.name.lowercase() }

/**
 * Whether the runtime behind [agent] carries a real provider catalog.
 *
 * Only OpenCode's own runtimes do. Claude Code, Antigravity and Codex publish a synthetic
 * one-provider catalog and sign in on their own settings screens, so an "add a key" row pointing
 * at OpenCode's provider screen could never connect them.
 */
internal fun runtimeHasProviderCatalog(agent: LocalAgent?): Boolean = agent == null || agent == LocalAgent.OPEN_CODE

/** Matches a provider against the picker's search box, the way its models are matched. */
internal fun providerMatchesQuery(
    provider: OpenCodeProvider,
    query: String,
): Boolean =
    query.isBlank() ||
        provider.name.contains(query, ignoreCase = true) ||
        provider.id.contains(query, ignoreCase = true)
