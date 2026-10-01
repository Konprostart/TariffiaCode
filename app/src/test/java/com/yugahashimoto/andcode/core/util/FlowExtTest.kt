package com.yugahashimoto.andcode.core.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

private const val GRACE_MILLIS = 60_000L

/**
 * Covers the "sessions" wake-lock lease grace period: a momentary SSE/HTTP blip must not release
 * the lease, but a real end of every session must still release it promptly. See
 * [com.yugahashimoto.andcode.AndCodeApplication]'s use of [debounceFalseEdge] on the `"sessions"`
 * lease bridge.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowExtTest {
    @Test
    fun `passes a transition to true through immediately`() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val results = mutableListOf<Boolean>()
            try {
                // Stays open, so this measures the true edge alone rather than also picking up the
                // closing false an ending upstream would add.
                scope.launch {
                    flow {
                        emit(true)
                        awaitCancellation()
                    }.debounceFalseEdge(GRACE_MILLIS).collect { results += it }
                }
                runCurrent()

                assertEquals(listOf(true), results)
            } finally {
                scope.cancel()
            }
        }

    /** The exact scenario this exists for: a blip that recovers must look like nothing happened. */
    @Test
    fun `a drop that recovers within the grace window never emits false`() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val results = mutableListOf<Boolean>()
            val source =
                flow {
                    emit(true)
                    delay(1_000L)
                    emit(false)
                    delay(5_000L) // well inside the grace window
                    emit(true)
                    // Stays open, so the recovery is the last thing the assertion sees rather than
                    // the closing false an ending upstream would add on top of it.
                    awaitCancellation()
                }
            try {
                scope.launch { source.debounceFalseEdge(GRACE_MILLIS).collect { results += it } }
                advanceTimeBy(10_000L)
                runCurrent()

                assertEquals(listOf(true), results)
            } finally {
                scope.cancel()
            }
        }

    /** A real end - the drop holding for the whole window - must still release the lease. */
    @Test
    fun `a drop that holds for the whole grace window emits false`() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val results = mutableListOf<Boolean>()
            val source =
                flow {
                    emit(true)
                    delay(1_000L)
                    emit(false)
                }
            try {
                scope.launch { source.debounceFalseEdge(GRACE_MILLIS).collect { results += it } }
                advanceTimeBy(1_000L)
                runCurrent()
                assertEquals(listOf(true), results)

                advanceTimeBy(GRACE_MILLIS)
                runCurrent()
                assertEquals(listOf(true, false), results)
            } finally {
                scope.cancel()
            }
        }

    /** A second blip after the first has already recovered must be judged on its own window. */
    @Test
    fun `each drop gets its own grace window`() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val results = mutableListOf<Boolean>()
            val source =
                flow {
                    emit(true)
                    delay(1_000L)
                    emit(false) // recovers
                    delay(5_000L)
                    emit(true)
                    delay(1_000L)
                    emit(false) // this one holds
                }
            try {
                scope.launch { source.debounceFalseEdge(GRACE_MILLIS).collect { results += it } }
                advanceTimeBy(7_000L)
                runCurrent()
                assertEquals(listOf(true), results)

                advanceTimeBy(GRACE_MILLIS)
                runCurrent()
                assertEquals(listOf(true, false), results)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `never having been true emits nothing for a drop`() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val results = mutableListOf<Boolean>()
            try {
                scope.launch {
                    flow { emit(false) }.debounceFalseEdge(GRACE_MILLIS).collect { results += it }
                }
                advanceTimeBy(GRACE_MILLIS)
                runCurrent()

                assertEquals(emptyList<Boolean>(), results)
            } finally {
                scope.cancel()
            }
        }

    /**
     * An upstream that ends on `true` must still hand its collector the closing `false`. For the
     * `"sessions"` lease this transform feeds, a `true` nobody ever takes back is a wake lock held
     * for the rest of the process.
     */
    @Test
    fun `an upstream that ends while true still emits the closing false`() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val results = mutableListOf<Boolean>()
            try {
                scope.launch {
                    flow { emit(true) }.debounceFalseEdge(GRACE_MILLIS).collect { results += it }
                }
                runCurrent()

                assertEquals(listOf(true, false), results)
            } finally {
                scope.cancel()
            }
        }
}
