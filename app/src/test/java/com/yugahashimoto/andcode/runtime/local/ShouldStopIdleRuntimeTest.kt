package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShouldStopIdleRuntimeTest {
    private val ready = LocalRuntimeStatus.Ready(version = "1.0.0", port = 4096)
    private val timeout = 15 * 60 * 1000L

    @Test
    fun `stops once ready, work-free, backgrounded, adb-disconnected and past the timeout`() {
        assertTrue(
            shouldStopIdleRuntime(
                status = ready,
                hasActiveWork = false,
                appInForeground = false,
                adbConnected = false,
                idleForMillis = timeout,
                timeoutMillis = timeout,
            ),
        )
    }

    @Test
    fun `does not stop before the timeout elapses`() {
        assertFalse(
            shouldStopIdleRuntime(
                status = ready,
                hasActiveWork = false,
                appInForeground = false,
                adbConnected = false,
                idleForMillis = timeout - 1,
                timeoutMillis = timeout,
            ),
        )
    }

    /** A lease held through RuntimeWorkTracker - a chat run, a schedule, an in-flight adb command - always blocks it. */
    @Test
    fun `does not stop while work is active`() {
        assertFalse(
            shouldStopIdleRuntime(
                status = ready,
                hasActiveWork = true,
                appInForeground = false,
                adbConnected = false,
                idleForMillis = timeout,
                timeoutMillis = timeout,
            ),
        )
    }

    @Test
    fun `does not stop while the app is in the foreground`() {
        assertFalse(
            shouldStopIdleRuntime(
                status = ready,
                hasActiveWork = false,
                appInForeground = true,
                adbConnected = false,
                idleForMillis = timeout,
                timeoutMillis = timeout,
            ),
        )
    }

    /**
     * A live wireless-debugging link is not leased as work (see AdbConnectionManager), but it still
     * has to block the shutdown - killing the runtime out from under an active debugging session
     * would be as disruptive as freezing a chat run.
     */
    @Test
    fun `does not stop while adb is connected`() {
        assertFalse(
            shouldStopIdleRuntime(
                status = ready,
                hasActiveWork = false,
                appInForeground = false,
                adbConnected = true,
                idleForMillis = timeout,
                timeoutMillis = timeout,
            ),
        )
    }

    /** An install, update, rollback or restore is never Ready, so it can never satisfy this policy. */
    @Test
    fun `never stops a runtime that is not ready`() {
        assertFalse(
            shouldStopIdleRuntime(
                status = LocalRuntimeStatus.Installing(progress = 0.5f, step = "unpacking"),
                hasActiveWork = false,
                appInForeground = false,
                adbConnected = false,
                idleForMillis = timeout,
                timeoutMillis = timeout,
            ),
        )
        assertFalse(
            shouldStopIdleRuntime(
                status = LocalRuntimeStatus.Starting("1.0.0", 4096),
                hasActiveWork = false,
                appInForeground = false,
                adbConnected = false,
                idleForMillis = timeout,
                timeoutMillis = timeout,
            ),
        )
        assertFalse(
            shouldStopIdleRuntime(
                status = LocalRuntimeStatus.Stopped("1.0.0", 4096),
                hasActiveWork = false,
                appInForeground = false,
                adbConnected = false,
                idleForMillis = timeout,
                timeoutMillis = timeout,
            ),
        )
    }
}
