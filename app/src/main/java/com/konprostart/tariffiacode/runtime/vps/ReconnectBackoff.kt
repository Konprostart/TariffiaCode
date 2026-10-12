package com.konprostart.tariffiacode.runtime.vps

import kotlin.random.Random

/**
 * Backoff schedule for rebuilding a dropped connection.
 *
 * Exponential because a radio that just dropped a tunnel is usually still settling, and because a
 * VPS that is genuinely unreachable should be retried sparingly rather than in a tight loop.
 * Jitter because every client that lost the same connection would otherwise retry in lockstep,
 * which turns a brief outage into a synchronised stampede when the server comes back.
 *
 * Pure and injectable, so the schedule can be asserted without a clock.
 */
internal class ReconnectBackoff(
    private val initialDelayMillis: Long = DEFAULT_INITIAL_DELAY_MILLIS,
    private val maxDelayMillis: Long = DEFAULT_MAX_DELAY_MILLIS,
    private val multiplier: Int = DEFAULT_MULTIPLIER,
    /** Fraction of the step to spread jitter over, in tenths (4 = +/-20%). */
    private val jitterTenths: Int = DEFAULT_JITTER_TENTHS,
    private val random: Random = Random.Default,
) {
    /** Delay before attempt number [attempt], counting from zero. */
    fun delayMillis(attempt: Int): Long {
        require(attempt >= 0) { "attempt must not be negative" }
        val step = exponentialStep(attempt)
        if (jitterTenths <= 0) return step
        val spread = (step * jitterTenths) / 10
        if (spread <= 0) return step
        return (step - spread / 2 + random.nextLong(spread)).coerceAtLeast(1L)
    }

    /** The un-jittered, capped step for [attempt]. */
    private fun exponentialStep(attempt: Int): Long {
        var step = initialDelayMillis.coerceIn(1L, maxDelayMillis)
        repeat(attempt) { step = (step * multiplier).coerceAtMost(maxDelayMillis) }
        return step
    }

    companion object {
        /** Quick enough that a brief handover is invisible, slow enough not to spin. */
        const val DEFAULT_INITIAL_DELAY_MILLIS = 1_000L

        /** A minute of trying is plenty; past that the user is better served by a visible failure. */
        const val DEFAULT_MAX_DELAY_MILLIS = 30_000L

        const val DEFAULT_MULTIPLIER = 2

        const val DEFAULT_JITTER_TENTHS = 4
    }
}
