package com.konprostart.tariffiacode.feature.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.konprostart.tariffiacode.core.router.KeyStore
import com.konprostart.tariffiacode.core.router.RouterConfigLoader
import com.konprostart.tariffiacode.core.router.RouterConfigSource
import com.konprostart.tariffiacode.core.router.RouterKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One provider the routing table can ask for, and whether it is ready to be asked. */
data class RouterProviderUi(
    val provider: String,
    val hasKey: Boolean,
    /** The last few characters only. A key is shown masked even to the person who typed it. */
    val keyHint: String,
)

/** Everything this screen shows. One object, because there is nothing here worth a state machine. */
data class RouterKeysUiState(
    val providers: List<RouterProviderUi> = emptyList(),
    val editing: String? = null,
    val draft: String = "",
    val message: String? = null,
    val tableOrigin: RouterConfigSource.Origin? = null,
    val tableWarning: String? = null,
    val userTablePath: String? = null,
    val storeAvailable: Boolean = true,
)

/**
 * Reads and writes the router's own key store.
 *
 * Deliberately the only thing in the app that touches [RouterKeys]. The OpenCode provider keys are a
 * separate store with a separate screen, and nothing here can reach them - two worlds stay two
 * worlds unless merging them is asked for.
 */
class RouterKeysViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val store: KeyStore = RouterKeys(application)

    private val _state = MutableStateFlow(RouterKeysUiState())
    val state: StateFlow<RouterKeysUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val loaded = RouterConfigLoader(getApplication()).load()
        val providers =
            loaded.table.config.chains
                .flatMap { it.models }
                .map { it.provider }
                .distinct()
                .sorted()
        _state.update {
            it.copy(
                providers =
                    providers.map { provider ->
                        val key = store.keyFor(provider)
                        RouterProviderUi(
                            provider = provider,
                            hasKey = !key.isNullOrBlank(),
                            keyHint = key?.let { masked(it) }.orEmpty(),
                        )
                    },
                tableOrigin = loaded.origin,
                tableWarning = loaded.rejected,
                userTablePath = RouterConfigLoader(getApplication()).userTableFile()?.absolutePath,
                storeAvailable = RouterKeys(getApplication()).isAvailable(),
            )
        }
    }

    fun startEditing(provider: String) {
        // Deliberately blank rather than pre-filled: putting a stored key into a text field is how it
        // ends up in a screenshot, a clipboard, or a bug report.
        _state.update { it.copy(editing = provider, draft = "", message = null) }
    }

    fun cancelEditing() {
        _state.update { it.copy(editing = null, draft = "") }
    }

    fun onDraftChanged(value: String) {
        _state.update { it.copy(draft = value) }
    }

    fun save() {
        val current = _state.value
        val provider = current.editing ?: return
        val key = current.draft.trim()
        if (key.isEmpty()) {
            _state.update { it.copy(message = "A blank key is not a key") }
            return
        }
        store.setKey(provider, key)
        _state.update {
            it.copy(editing = null, draft = "", message = "Saved $provider")
        }
        refresh()
    }

    fun remove(provider: String) {
        store.removeKey(provider)
        _state.update { it.copy(message = "Removed $provider") }
        refresh()
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    /** Everything but the last four characters, which is enough to tell two keys apart. */
    private fun masked(key: String): String = "..." + key.takeLast(4)
}
