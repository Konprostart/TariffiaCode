package com.konprostart.tariffiacode.runtime.vps

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReconnectBackoffTest {
    private val fixed = ReconnectBackoff(random = Random(7))

    @Test
    fun `the delay grows with each attempt`() {
        val delays = (0..4).map { fixed.delayMillis(it) }

        // Jitter can invert two neighbouring steps, so compare the trend over the whole run rather
        // than adjacent pairs.
        assertTrue("expected growth, got $delays", delays.first() < delays.last())
        assertTrue("expected growth, got $delays", delays[0] < delays[3])
    }

    @Test
    fun `every delay stays inside the jitter band around its step`() {
        val backoff = ReconnectBackoff(random = Random(11))
        // Only the attempts whose step is still below the 30s cap, so the band is around the
        // exponential step itself; the cap has its own test.
        (0..4).forEach { attempt ->
            val step = 1_000L shl attempt
            repeat(50) {
                val delay = backoff.delayMillis(attempt)
                assertTrue(
                    "attempt $attempt produced $delay, outside the band around $step",
                    delay >= step * 0.79L && delay <= step * 1.21L,
                )
            }
        }
    }

    @Test
    fun `the cap is a ceiling on the result, not only on the step`() {
        // Jitter is centred on the step, so without a ceiling on the returned value a capped attempt
        // could still hand out a fifth more than the cap.
        val backoff = ReconnectBackoff(maxDelayMillis = 5_000L, random = Random(3))
        (0..20).forEach { attempt ->
            assertTrue("attempt $attempt exceeded the cap", backoff.delayMillis(attempt) <= 5_000L)
        }
    }

    @Test
    fun `the delay is deterministic for a seeded source`() {
        assertTrue(fixed.delayMillis(2) == ReconnectBackoff(random = Random(7)).delayMillis(2))
        assertTrue(fixed.delayMillis(2) > 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative attempt is rejected`() {
        fixed.delayMillis(-1)
    }
}
