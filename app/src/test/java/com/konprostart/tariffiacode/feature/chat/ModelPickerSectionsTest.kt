package com.konprostart.tariffiacode.feature.chat

import com.konprostart.tariffiacode.core.api.OpenCodeProvider
import com.konprostart.tariffiacode.runtime.LocalAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPickerSectionsTest {
    private fun provider(
        id: String,
        name: String = id,
    ) = OpenCodeProvider(id = id, name = name)

    @Test
    fun `unconfigured providers are the catalog minus the connected set`() {
        val all =
            listOf(
                provider("anthropic", "Anthropic"),
                provider("zai", "Z.AI"),
                provider("ollama", "Ollama"),
            )

        assertEquals(
            listOf("anthropic", "ollama"),
            unconfiguredProviders(all, setOf("zai")).map { it.id },
        )
    }

    @Test
    fun `unconfigured providers are sorted by display name, not id`() {
        val all = listOf(provider("zai", "Z.AI"), provider("anthropic", "Anthropic"))

        assertEquals(listOf("anthropic", "zai"), unconfiguredProviders(all, emptySet()).map { it.id })
    }

    @Test
    fun `an empty catalog yields no unconfigured providers`() {
        assertTrue(unconfiguredProviders(emptyList(), setOf("anthropic")).isEmpty())
    }

    @Test
    fun `a catalog that is fully connected yields no unconfigured providers`() {
        val all = listOf(provider("anthropic"), provider("zai"))

        assertTrue(unconfiguredProviders(all, setOf("anthropic", "zai")).isEmpty())
    }

    @Test
    fun `provider search matches name and id case-insensitively`() {
        val zai = provider("zai", "Z.AI")

        assertTrue(providerMatchesQuery(zai, ""))
        assertTrue(providerMatchesQuery(zai, "z.a"))
        assertTrue(providerMatchesQuery(zai, "ZAI"))
        assertFalse(providerMatchesQuery(zai, "anthropic"))
    }

    @Test
    fun `only opencode runtimes carry a provider catalog`() {
        // Null is the OpenCode protocol's own marker: the VPS and remote runtimes leave the agent
        // unset, and they are exactly the runtimes whose providers the settings screen can connect.
        assertTrue(runtimeHasProviderCatalog(null))
        assertTrue(runtimeHasProviderCatalog(LocalAgent.OPEN_CODE))
        assertFalse(runtimeHasProviderCatalog(LocalAgent.CLAUDE_CODE))
        assertFalse(runtimeHasProviderCatalog(LocalAgent.ANTIGRAVITY))
        assertFalse(runtimeHasProviderCatalog(LocalAgent.CODEX))
    }
}
