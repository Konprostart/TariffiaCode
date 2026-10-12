package com.konprostart.tariffiacode.feature.settings

import com.konprostart.tariffiacode.core.api.ProviderAuthAuthorization
import com.konprostart.tariffiacode.core.api.ProviderAuthMethod

data class ProviderAuthDialogState(
    val providerId: String,
    val providerName: String,
    val methods: List<ProviderAuthMethod>,
    val methodIndex: Int? = null,
    val inputs: Map<String, String> = emptyMap(),
    val apiKey: String = "",
    val authorization: ProviderAuthAuthorization? = null,
    val isSubmitting: Boolean = false,
    val failed: Boolean = false,
    val error: String? = null,
) {
    val selectedMethod: ProviderAuthMethod?
        get() = methodIndex?.let(methods::getOrNull)

    /**
     * Fills in the server's real method list once it arrives.
     *
     * The model picker opens this dialog straight from an "add a key" row, before
     * `refreshProviderAuth` has answered, so the dialog starts from the API-key-only fallback.
     * Replacing the list is safe only while the user has not picked a method yet, and an empty
     * answer leaves the fallback in place.
     */
    fun withLoadedMethods(loaded: List<ProviderAuthMethod>): ProviderAuthDialogState =
        if (loaded.isEmpty() || methodIndex != null) this else copy(methods = loaded)

    val visiblePrompts
        get() = selectedMethod?.prompts.orEmpty().filter { it.isVisible(inputs) }

    val promptsComplete: Boolean
        get() =
            visiblePrompts.all { prompt ->
                when (prompt.type) {
                    "text", "select" -> !inputs[prompt.key].isNullOrBlank()
                    else -> true
                }
            }
}

enum class ProviderAuthNotice {
    CONNECTED,
    DISCONNECTED,
}

/** Draft state for the "add custom provider" dialog: an OpenAI-compatible endpoint the user is registering by hand. */
data class CustomProviderDialogState(
    val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val models: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
)
