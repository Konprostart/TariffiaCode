package com.konprostart.tariffiacode.runtime.local

import kotlinx.coroutines.delay

/**
 * The graceful-stop sequence shared by Antigravity's abort and disconnect paths.
 *
 * A running turn is first asked to stop (ESC written to its stdin) and is only force-killed if it is
 * still alive after [GRACE_MILLIS]. This used to be a blocking two-second sleep inside the runtime's
 * `abort`, which the chat calls from the UI thread; [delay] is a cancellable suspension instead, so
 * the wait never blocks the caller's thread and cancellation propagates normally.
 */
internal object AntigravityAbort {
    const val GRACE_MILLIS = 2000L

    /**
     * [requestStop] is best-effort (a broken pipe on an already-dying process must not skip the
     * fallback), then waits [graceMillis], then calls [terminate] only if [isAlive] is still true.
     */
    suspend fun gracefully(
        graceMillis: Long = GRACE_MILLIS,
        requestStop: () -> Unit,
        isAlive: () -> Boolean,
        terminate: () -> Unit,
    ) {
        runCatching(requestStop)
        delay(graceMillis)
        if (isAlive()) terminate()
    }
}
