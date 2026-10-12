package com.konprostart.tariffiacode.feature.settings

import com.konprostart.tariffiacode.core.api.ProviderAuthMethod
import com.konprostart.tariffiacode.core.api.ProviderAuthPrompt
import com.konprostart.tariffiacode.core.api.ProviderAuthWhen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderAuthUiModelsTest {
    private val method =
        ProviderAuthMethod(
            type = "oauth",
            label = "Workspace login",
            prompts =
                listOf(
                    ProviderAuthPrompt(
                        type = "select",
                        key = "region",
                        message = "Region",
                    ),
                    ProviderAuthPrompt(
                        type = "text",
                        key = "tenant",
                        message = "Tenant",
                        whenCondition = ProviderAuthWhen("region", "eq", "us"),
                    ),
                ),
        )

    @Test
    fun `only visible prompts are required`() {
        val nonUs =
            ProviderAuthDialogState(
                providerId = "custom",
                providerName = "Custom",
                methods = listOf(method),
                methodIndex = 0,
                inputs = mapOf("region" to "eu"),
            )
        val usWithoutTenant = nonUs.copy(inputs = mapOf("region" to "us"))
        val usComplete = usWithoutTenant.copy(inputs = mapOf("region" to "us", "tenant" to "acme"))

        assertTrue(nonUs.promptsComplete)
        assertFalse(usWithoutTenant.promptsComplete)
        assertTrue(usComplete.promptsComplete)
    }

    @Test
    fun `the fallback method list is replaced when the real one arrives`() {
        val fallback =
            ProviderAuthDialogState(
                providerId = "github-copilot",
                providerName = "GitHub Copilot",
                methods = listOf(ProviderAuthMethod(type = "api", label = "API key")),
            )

        assertEquals(listOf(method), fallback.withLoadedMethods(listOf(method)).methods)
    }

    @Test
    fun `a loaded list never replaces a method the user already picked`() {
        val picked =
            ProviderAuthDialogState(
                providerId = "github-copilot",
                providerName = "GitHub Copilot",
                methods = listOf(ProviderAuthMethod(type = "api", label = "API key")),
                methodIndex = 0,
            )

        assertEquals(picked, picked.withLoadedMethods(listOf(method)))
    }

    @Test
    fun `an empty answer leaves the fallback in place`() {
        val fallback =
            ProviderAuthDialogState(
                providerId = "zai",
                providerName = "Z.AI",
                methods = listOf(ProviderAuthMethod(type = "api", label = "API key")),
            )

        assertEquals(fallback, fallback.withLoadedMethods(emptyList()))
    }
}
