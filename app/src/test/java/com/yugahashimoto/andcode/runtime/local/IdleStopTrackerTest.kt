package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Test

class IdleStopTrackerTest {
    @Test
    fun `reports zero on the first idle tick`() {
        val tracker = IdleStopTracker(nowMillis = { 1_000L })

        assertEquals(0L, tracker.update(idleNow = true))
    }

    @Test
    fun `accumulates elapsed time while idle continues to hold`() {
        var now = 1_000L
        val tracker = IdleStopTracker(nowMillis = { now })

        tracker.update(idleNow = true)
        now += 5_000L

        assertEquals(5_000L, tracker.update(idleNow = true))
    }

    @Test
    fun `resets to zero once the idle condition stops holding`() {
        var now = 1_000L
        val tracker = IdleStopTracker(nowMillis = { now })
        tracker.update(idleNow = true)
        now += 5_000L
        tracker.update(idleNow = true)

        now += 1_000L
        assertEquals(0L, tracker.update(idleNow = false))
    }

    /** A fresh idle window after a reset starts counting from zero again, not from the old start. */
    @Test
    fun `starts a new window after idle drops and returns`() {
        var now = 1_000L
        val tracker = IdleStopTracker(nowMillis = { now })
        tracker.update(idleNow = true)
        now += 5_000L
        tracker.update(idleNow = false)

        now += 2_000L
        assertEquals(0L, tracker.update(idleNow = true))

        now += 3_000L
        assertEquals(3_000L, tracker.update(idleNow = true))
    }
}
