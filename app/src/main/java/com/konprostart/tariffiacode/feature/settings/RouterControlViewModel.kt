package com.konprostart.tariffiacode.feature.settings

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.konprostart.tariffiacode.core.router.RouterService
import com.konprostart.tariffiacode.core.router.RouterStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
            // `pending` is deliberately not touched here. The screen polls every two seconds, so a
            // poll landing in the middle of a start or a stop used to clear the flag and hand the
            // switch back while the operation it was waiting for was still running. Only the start
            // and stop paths below own that field now.
            _state.update { it.copy(status = status, failure = failure) }
        }
    }

    fun start() {
        _state.update { it.copy(pending = true, failure = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { RouterService.start(context) }
                // Give the service time to write its endpoint before reporting on it.
                repeat(30) {
                    val status = withContext(Dispatchers.IO) { RouterService.status(context) }
                    if (status is RouterStatus.Running) {
                        _state.update { it.copy(status = status, failure = null) }
                        return@launch
                    }
                    delay(300)
                }
                val failure = withContext(Dispatchers.IO) { RouterService.lastFailure(context) }
                val status = withContext(Dispatchers.IO) { RouterService.status(context) }
                _state.update { it.copy(status = status, failure = failure) }
            } finally {
                // Nothing else clears this. refresh() deliberately does not, and the screen polls it
                // every two seconds, so a throw here would leave the switch disabled for the life of
                // the ViewModel - which is what startForegroundService from the background on API 31+
                // can still do.
                _state.update { it.copy(pending = false) }
            }
        }
    }

    fun stop() {
        _state.update { it.copy(pending = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.stopService(Intent(context, RouterService::class.java))
                }
                // A while loop rather than `repeat`: breaking out of an inline lambda across a
                // suspension point is not allowed, and the status read below is a suspension point.
                var polls = STOP_STATUS_POLLS
                while (polls-- > 0) {
                    val status = withContext(Dispatchers.IO) { RouterService.status(context) }
                    if (status is RouterStatus.NotRunning) break
                    delay(200)
                }
                // Whether or not the poll saw it stop - and running it *only* on timeout would be the
                // wrong way round. A process Android killed reports NotRunning on the very first
                // poll, and that is exactly the case where nothing in the service process is left to
                // clean up after itself, so an early exit here would skip the one step that needs
                // doing. The file then survives holding a dead port and a live bearer token, and
                // every agent started before the next router start is handed a base URL that answers
                // nothing - which is the contract AgentRouterEndpoint says must never happen.
                //
                // The older service cleans its own endpoint file in stop(); there is nothing more
                // to reconcile from the screen.
                val status = withContext(Dispatchers.IO) { RouterService.status(context) }
                _state.update { it.copy(status = status) }
            } finally {
                _state.update { it.copy(pending = false) }
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

/** How long stop() waits for the service to go quiet before it cleans up after it. */
private const val STOP_STATUS_POLLS = 20
