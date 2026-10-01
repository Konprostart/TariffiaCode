package com.yugahashimoto.andcode.core.util

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Passes every transition to `true` through immediately, but only passes a transition to `false`
 * through once it has held for [graceMillis] without flipping back to `true`.
 *
 * Written for [com.yugahashimoto.andcode.AndCodeApplication]'s `"sessions"` wake-lock lease, which
 * follows [com.yugahashimoto.andcode.data.repository.RuntimeActivityRepository]'s
 * `activeSessionIds`: that set is cleared the instant the runtime target leaves
 * [com.yugahashimoto.andcode.runtime.RuntimeState.Connected]/`Connecting`, including for a
 * momentary SSE/HTTP blip during a long agent run. Releasing the lease - and with it the wake lock
 * - on that same instant lets the device suspend mid-run and freezes the proot child, which is
 * exactly the failure the wake-lock rework exists to prevent. Debouncing only the false edge means
 * a real end (the flag staying `false` for the whole grace window) still releases the lease
 * promptly, while a blip that recovers within the window never releases it at all -
 * `activeSessionIds` itself is untouched, so every other consumer of it still sees the drop
 * immediately; only this one downstream lease waits it out.
 *
 * Implemented as a plain, testable [Flow] transform - not inline in the application class - so the
 * "blip vs. real end" distinction can be verified with a virtual clock instead of a live wake lock.
 * Runs the receiving side of a [Channel.CONFLATED] buffer in a single coroutine so `current` is
 * never touched from more than one thread at a time, unlike a shared `var` written from both a
 * collector and a delayed callback would be.
 */
fun Flow<Boolean>.debounceFalseEdge(graceMillis: Long): Flow<Boolean> =
    channelFlow {
        val values = Channel<Boolean>(Channel.CONFLATED)
        // Closing the channel when the upstream ends is what lets this transform terminate rather
        // than park forever on a receive that can never be satisfied. The app's own caller passes a
        // StateFlow, which never completes, but a transform in core/util has no business hanging on
        // the finite flow some later caller hands it.
        launch {
            collect { values.send(it) }
            values.close()
        }
        var current = false
        while (true) {
            val value = values.receiveCatching().getOrNull()
            if (value == null) {
                // The upstream ended while the last thing seen was `true`. Leaving it at that would
                // strand every downstream on a `true` that can never be taken back - for the lease
                // this exists for, a wake lock held for the rest of the process.
                if (current) send(false)
                break
            }
            if (value) {
                if (!current) {
                    current = true
                    send(true)
                }
                continue
            }
            if (!current) continue
            // True once the drop recovered, null once the window ran out with it still standing.
            val recovered =
                withTimeoutOrNull(graceMillis) {
                    var next = values.receiveCatching().getOrNull()
                    // Anything but a recovery keeps waiting out the rest of the window.
                    while (next == false) {
                        next = values.receiveCatching().getOrNull()
                    }
                    // Null means the upstream ended with the drop still standing. No recovery can
                    // arrive any more, but the window is still the window: waiting it out rather
                    // than releasing early keeps the timing independent of how the flow happens to
                    // end, and parks instead of spinning on a channel that only ever reports closed.
                    next ?: awaitCancellation()
                }
            if (recovered != true) {
                current = false
                send(false)
            }
            if (values.isClosedForReceive) break
        }
    }
