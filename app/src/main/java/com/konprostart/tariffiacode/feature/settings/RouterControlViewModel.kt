package com.konprostart.tariffiacode.feature.settings

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.konprostart.tariffiacode.core.router.RouterService
import com.konprostart.tariffiacode.core.router.RouterStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.viewModelScope
import kotlinx.coroutines.withContext

/**
 * Whether the router is on, and whether anyone can tell.
 *
 * The switch is only honest if the state it shows is the service's own. So this reads the same file
 * the service writes, and keeps looking for a little while after asking it to start - a foreground
 * service takes a moment, and a switch that snaps back to "off" because it asked too early teaches
 * the user to distrust it.
 */
class RouterControlViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val context = application

    private val _state = MutableStateFlow(RouterControlUiState())
    val state: StateFlow<RouterControlUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { RouterService.status(context) }
            val failure = withContext(Dispatchers.IO) { RouterService.lastFailure(context) }
            _state.update { it.copy(status = status, failure = failure, pending = false) }
        }
    }

    fun start() {
        _state.update { it.copy(pending = true, failure = null) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { RouterService.start(context) }
            // Give the service time to write its endpoint before reporting on it.
            repeat(30) {
                val status = withContext(Dispatchers.IO) { RouterService.status(context) }
                if (status is RouterStatus.Running) {
                    _state.update { it.copy(status = status, pending = false, failure = null) }
                    return@launch
                }
                delay(300)
            }
            val failure = withContext(Dispatchers.IO) { RouterService.lastFailure(context) }
            _state.update {
                it.copy(
                    status = withContext(Dispatchers.IO) { RouterService.status(context) },
                    failure = failure,
                    pending = false,
                )
            }
        }
    }

    fun stop() {
        _state.update { it.copy(pending = true) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                context.stopService(Intent(context, RouterService::class.java))
            }
            repeat(20) {
                val status = withContext(Dispatchers.IO) { RouterService.status(context) }
                if (status is RouterStatus.NotRunning) {
                    _state.update { it.copy(status = status, pending = false) }
                    return@launch
                }
                delay(200)
            }
            _state.update {
                it.copy(status = withContext(Dispatchers.IO) { RouterService.status(context) }, pending = false)
            }
        }
    }
}

/** What the screen shows. */
data class RouterControlUiState(
    val status: RouterStatus = RouterStatus.NotRunning,
    /** Why the last attempt to start did not work, when it did not. */
    val failure: String? = null,
    /** A start or stop is in flight; the switch waits rather than snapping back. */
    val pending: Boolean = false,
) {
    val isRunning: Boolean get() = status is RouterStatus.Running
    val port: Int? get() = (status as? RouterStatus.Running)?.port
}
