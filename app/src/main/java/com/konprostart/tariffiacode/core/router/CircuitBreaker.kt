package com.konprostart.tariffiacode.core.router

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps a provider that is failing from costing every request a timeout.
 *
 * A chain is walked in order, so a provider that is down is paid for first - and if it hangs rather
 * than refusing, that is seconds of nothing on every single turn. The breaker is what stops the chain
 * from paying that repeatedly: after [failureThreshold] failures it is opened and skipped outright
 * until the cooldown passes, so the cost is paid once per cooldown instead of once per request.
 *
 * It is per provider, not per chain: two tiers that both name DeepSeek should not each keep their own
 * opinion about whether DeepSeek is working.
 *
 * [nowMs] is injected rather than read from the clock so the transitions can be tested without
 * sleeping, which is the only way to test a cooldown honestly.
 */
class CircuitBreaker(
    private val cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val states = ConcurrentHashMap<String, State>()

    /** Whether [provider] may be tried now, opening a half-open probe if its cooldown has passed. */
    fun allows(provider: String): Boolean {
        val state = states[provider] ?: return true
        return when (state.status) {
            Status.OPEN ->
                if (nowMs() - state.openedAt >= cooldownMillis) {
                    // One probe at a time: a burst of concurrent requests must not all be admitted
                    // on the same cooldown expiry and take a provider down that was just coming back.
                    if (states.replace(provider, state, state.copy(status = Status.HALF_OPEN, probes = AtomicInteger(1)))) {
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            Status.HALF_OPEN -> false
            Status.CLOSED -> true
        }
    }

    /**
     * Records a success and closes the breaker.
     *
     * After a half-open probe this is what promotes the provider back to normal; before it, it just
     * resets the failure count.
     */
    fun recordSuccess(provider: String) {
        states[provider] = State(status = Status.CLOSED, failures = 0, openedAt = 0, probes = AtomicInteger(0))
    }

    /**
     * Records a failure and opens the breaker once the threshold is reached.
     *
     * From half-open, a single failure re-opens immediately: the probe is the evidence, and one
     * failure is enough.
     */
    fun recordFailure(provider: String) {
        val state = states[provider]
        when {
            state == null || state.status == Status.HALF_OPEN ->
                states[provider] = State(status = Status.OPEN, failures = 1, openedAt = nowMs(), probes = AtomicInteger(0))
            else -> {
                val failures = state.failures + 1
                states[provider] =
                    if (failures >= failureThreshold) {
                        State(status = Status.OPEN, failures = failures, openedAt = nowMs(), probes = AtomicInteger(0))
                    } else {
                        State(status = Status.CLOSED, failures = failures, openedAt = 0, probes = AtomicInteger(0))
                    }
            }
        }
    }

    /** The current status, for a status line or a test. */
    fun statusOf(provider: String): Status = states[provider]?.status ?: Status.CLOSED

    /** Forgets every provider. Used when the router is reconfigured. */
    fun reset() = states.clear()

    enum class Status {
        /** Serving normally. */
        CLOSED,

        /** Skipping this provider until the cooldown passes. */
        OPEN,

        /** One probe admitted to see whether the provider has recovered. */
        HALF_OPEN,
    }

    private data class State(
        val status: Status,
        val failures: Int,
        val openedAt: Long,
        val probes: AtomicInteger,
    )

    companion object {
        /** Long enough that a provider gets a real chance to come back, short enough to not be forgotten. */
        const val DEFAULT_COOLDOWN_MILLIS = 60_000L
        const val DEFAULT_FAILURE_THRESHOLD = 2
    }
}
