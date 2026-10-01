package com.yugahashimoto.andcode.core.lifecycle

/**
 * Turns a stream of [AppForeground.foreground] emissions into "the app just entered the
 * foreground" events - a rising edge from `false` (or from no prior emission at all) to `true`.
 *
 * That includes the app's own cold start: [com.yugahashimoto.andcode.AndCodeApplication]'s
 * foreground observer is the sole driver of
 * [com.yugahashimoto.andcode.startup.RuntimeAutoStartTrigger.AppLaunch] now that
 * [com.yugahashimoto.andcode.startup.RuntimeAutoStartInitializer.create] no longer restores the
 * runtime itself, so the very first transition has to fire here too, not just later returns from
 * the background.
 *
 * Kept as a tiny, explicitly stateful class rather than inline Flow operators so the transition
 * logic - the part that actually decides when to act - is testable with plain JUnit instead of a
 * coroutine test harness.
 */
internal class ForegroundReturnDetector {
    private var wasForeground = false

    /**
     * Call with every [AppForeground.foreground] emission, in order. Returns true exactly when
     * [inForeground] is true and the previous call (or the absence of one) reported false - an
     * actual transition into the foreground, not a redundant repeat of one already reported.
     */
    fun onForegroundChanged(inForeground: Boolean): Boolean {
        val enteredForeground = inForeground && !wasForeground
        wasForeground = inForeground
        return enteredForeground
    }
}
