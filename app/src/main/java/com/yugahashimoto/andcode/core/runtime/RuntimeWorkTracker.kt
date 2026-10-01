package com.yugahashimoto.andcode.core.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Counts the work that must keep the device out of suspend.
 *
 * The local runtime's agent process runs as a proot child, so it freezes the instant the device
 * suspends - there is no way to resume it later like a killed service. [active] is the single
 * source of truth every wake-lock holder in the app consults before deciding it is safe to let the
 * CPU sleep: chat sessions, scheduled runs, install/update operations and a live ADB link each hold
 * their own [Lease] for as long as their work is in flight, and the device may only sleep once none
 * of them are.
 *
 * A tag is reference-counted rather than tracked as a single flag, because the same kind of work
 * (e.g. "sessions") can be in flight from more than one caller at once - the lock must not be
 * dropped until the last of them lets go.
 */
class RuntimeWorkTracker {
    private val lock = Any()
    private val counts = mutableMapOf<String, Int>()

    private val mutableActive = MutableStateFlow(false)
    val active: StateFlow<Boolean> = mutableActive.asStateFlow()

    private val mutableActiveTags = MutableStateFlow<Set<String>>(emptySet())

    /** Which tags currently hold a lease, for diagnostics screens - not required for correctness. */
    val activeTags: StateFlow<Set<String>> = mutableActiveTags.asStateFlow()

    interface Lease {
        /**
         * Drops this lease's hold on [tag]. Idempotent: calling it more than once (a `finally`
         * racing an explicit release, for instance) must never decrement the count twice.
         */
        fun release()
    }

    fun acquire(tag: String): Lease {
        synchronized(lock) {
            counts[tag] = (counts[tag] ?: 0) + 1
            publishLocked()
        }
        return TrackedLease(tag)
    }

    /** Holds a lease on [tag] for the duration of [block], releasing it however [block] exits. */
    suspend fun <T> withLease(
        tag: String,
        block: suspend () -> T,
    ): T {
        val lease = acquire(tag)
        try {
            return block()
        } finally {
            lease.release()
        }
    }

    private fun release(tag: String) {
        synchronized(lock) {
            val remaining = (counts[tag] ?: return) - 1
            if (remaining <= 0) counts.remove(tag) else counts[tag] = remaining
            publishLocked()
        }
    }

    /** Must be called while holding [lock]; publishes both flows from the same consistent count. */
    private fun publishLocked() {
        mutableActiveTags.value = counts.keys.toSet()
        mutableActive.value = counts.isNotEmpty()
    }

    /**
     * A single [AtomicBoolean] per lease instance, not per tag, is what makes [release] idempotent
     * without the tracker having to hand out distinguishable tokens: whichever caller wins the
     * compare-and-set is the only one that ever reaches [RuntimeWorkTracker.release].
     */
    private inner class TrackedLease(private val tag: String) : Lease {
        private val released = AtomicBoolean(false)

        override fun release() {
            if (released.compareAndSet(false, true)) release(tag)
        }
    }
}
