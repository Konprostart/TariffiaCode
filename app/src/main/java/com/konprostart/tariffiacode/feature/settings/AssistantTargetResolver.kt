package com.konprostart.tariffiacode.feature.settings

import com.konprostart.tariffiacode.core.api.OpenCodeProvider
import com.konprostart.tariffiacode.core.api.ProviderCatalog
import com.konprostart.tariffiacode.runtime.RuntimeState
import com.konprostart.tariffiacode.runtime.RuntimeTarget
import com.konprostart.tariffiacode.runtime.WorkspaceRef

/** Targets that can actually be named as an assistant Agent. Remote OpenCode servers have no agent identity. */
internal fun assistantTargets(targets: List<RuntimeTarget>): List<RuntimeTarget> =
    targets
        .filter { it.agent != null && it.state.value !is RuntimeState.Unavailable }
        .distinctBy { it.agent }

/** Models the selected agent can use, excluding providers that are not authenticated. */
internal fun assistantProviderOptions(catalog: ProviderCatalog): List<OpenCodeProvider> = catalog.all.filter { it.id in catalog.connected }

/**
 * The assistant's agent is usually not the runtime the chat has open, so its server is often still
 * stopped when the voice settings appear. Connecting first is what the chat catalogue already does;
 * without it the fetch failed and the picker offered the chat agent's models for an assistant that
 * cannot run them. An unreachable agent returns nothing, never another agent's catalogue.
 */
internal suspend fun loadAssistantProviders(target: RuntimeTarget): List<OpenCodeProvider> {
    if (runCatching { target.connect() }.getOrNull()?.isSuccess != true) return emptyList()
    return runCatching { assistantProviderOptions(target.listProviders()) }.getOrDefault(emptyList())
}

/** Prefer folders belonging to the assistant agent, with the chat catalogue as a safe fallback. */
internal fun assistantWorkspaceOptions(
    targetWorkspaces: List<WorkspaceRef>,
    fallbackWorkspaces: List<WorkspaceRef>,
): List<WorkspaceRef> = (targetWorkspaces.ifEmpty { fallbackWorkspaces }).distinctBy { it.path }
