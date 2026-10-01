package com.yugahashimoto.andcode.core.runtime

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RuntimeWorkTrackerTest {
    @Test
    fun `starts idle with no active work`() {
        val tracker = RuntimeWorkTracker()

        assertFalse(tracker.active.value)
        assertTrue(tracker.activeTags.value.isEmpty())
    }

    @Test
    fun `a single lease marks the tracker active until it is released`() {
        val tracker = RuntimeWorkTracker()

        val lease = tracker.acquire("sessions")
        assertTrue(tracker.active.value)
        assertEquals(setOf("sessions"), tracker.activeTags.value)

        lease.release()
        assertFalse(tracker.active.value)
        assertTrue(tracker.activeTags.value.isEmpty())
    }

    /** Two callers holding the same tag must not let each other's release drop the count to zero. */
    @Test
    fun `nested leases on the same tag stay active until every lease is released`() {
        val tracker = RuntimeWorkTracker()

        val first = tracker.acquire("schedule:1")
        val second = tracker.acquire("schedule:1")
        assertTrue(tracker.active.value)

        first.release()
        assertTrue("still held by the second lease", tracker.active.value)

        second.release()
        assertFalse(tracker.active.value)
    }

    @Test
    fun `distinct tags are tracked independently`() {
        val tracker = RuntimeWorkTracker()

        val sessions = tracker.acquire("sessions")
        val adb = tracker.acquire("adb")
        assertEquals(setOf("sessions", "adb"), tracker.activeTags.value)

        sessions.release()
        assertEquals(setOf("adb"), tracker.activeTags.value)
        assertTrue(tracker.active.value)

        adb.release()
        assertTrue(tracker.activeTags.value.isEmpty())
        assertFalse(tracker.active.value)
    }

    /** A double release (e.g. a `finally` racing an explicit release) must not underflow the count. */
    @Test
    fun `releasing the same lease twice does not underflow the count`() {
        val tracker = RuntimeWorkTracker()

        val guard = tracker.acquire("runtime-op")
        val other = tracker.acquire("runtime-op")

        guard.release()
        guard.release()
        guard.release()
        assertTrue("the other lease still holds the tag", tracker.active.value)

        other.release()
        assertFalse(tracker.active.value)
    }

    @Test
    fun `withLease releases even when the block throws`() =
        runTest {
            val tracker = RuntimeWorkTracker()

            runCatching {
                tracker.withLease("runtime-op") {
                    assertTrue(tracker.active.value)
                    error("boom")
                }
            }

            assertFalse(tracker.active.value)
        }

    @Test
    fun `withLease returns the block's result`() =
        runTest {
            val tracker = RuntimeWorkTracker()

            val result = tracker.withLease("schedule:42") { "done" }

            assertEquals("done", result)
            assertFalse(tracker.active.value)
        }

    /**
     * Leases are taken from the main thread, IO dispatchers and a foreground service at once in
     * production, so the counter has to survive genuine concurrent mutation, not just interleaved
     * coroutines on a single thread.
     */
    @Test
    fun `concurrent acquisition and release never leaves a stale active state`() {
        val tracker = RuntimeWorkTracker()
        val threadCount = 16
        val iterations = 500
        val pool = Executors.newFixedThreadPool(threadCount)
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)

        repeat(threadCount) { index ->
            pool.execute {
                ready.countDown()
                start.await()
                repeat(iterations) {
                    val lease = tracker.acquire("worker-$index")
                    lease.release()
                }
                done.countDown()
            }
        }

        ready.await()
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()

        assertFalse(tracker.active.value)
        assertTrue(tracker.activeTags.value.isEmpty())
    }
}
