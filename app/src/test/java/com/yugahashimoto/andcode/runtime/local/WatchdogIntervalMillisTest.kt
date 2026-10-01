package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchdogIntervalMillisTest {
    private val ready = LocalRuntimeStatus.Ready(version = "1.0.0", port = 4096)

    /** No wake lock is held while idle, so a tight poll buys nothing the runtime cannot do without. */
    @Test
    fun `a ready runtime with no active work backs off to the idle interval`() {
        assertEquals(60_000L, watchdogIntervalMillis(ready, hasActiveWork = false))
    }

    @Test
    fun `a ready runtime with active work keeps the tight interval`() {
        assertEquals(5_000L, watchdogIntervalMillis(ready, hasActiveWork = true))
    }

    /**
     * A bounded, in-progress operation needs its wake lock re-armed promptly, and a stopped runtime
     * needs the auto-restart check to notice quickly - both keep the tight interval regardless of
     * active work.
     */
    @Test
    fun `in-progress and stopped states keep the tight interval`() {
        assertEquals(5_000L, watchdogIntervalMillis(LocalRuntimeStatus.Stopped("1.0.0", 4096), hasActiveWork = false))
        assertEquals(5_000L, watchdogIntervalMillis(LocalRuntimeStatus.Starting("1.0.0", 4096), hasActiveWork = false))
        assertEquals(5_000L, watchdogIntervalMillis(LocalRuntimeStatus.Installing(0.5f, "unpacking"), hasActiveWork = false))
        assertEquals(
            5_000L,
            watchdogIntervalMillis(
                LocalRuntimeStatus.Updating(currentVersion = "1.0.0", targetVersion = "1.1.0", progress = null, step = "downloading"),
                hasActiveWork = false,
            ),
        )
    }

    /**
     * Broken, unsupported-ABI and never-installed are terminal states the auto-restart check cannot
     * recover from, so polling them every 5 seconds only burns battery for no chance of recovery.
     */
    @Test
    fun `terminal states back off to the idle interval`() {
        assertEquals(60_000L, watchdogIntervalMillis(LocalRuntimeStatus.NotInstalled, hasActiveWork = false))
        assertEquals(60_000L, watchdogIntervalMillis(LocalRuntimeStatus.Broken("missing rootfs"), hasActiveWork = false))
        assertEquals(60_000L, watchdogIntervalMillis(LocalRuntimeStatus.UnsupportedAbi("x86"), hasActiveWork = false))
    }
}
