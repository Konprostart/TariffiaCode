package com.yugahashimoto.andcode.feature.schedule

import com.yugahashimoto.andcode.core.api.OpenCodeMessage

/** How a scheduled run ended. */
sealed interface ScheduleCompletion {
    data object Completed : ScheduleCompletion

    /**
     * @param silent the turn was stopped on purpose (the stop button, a replacement prompt), so the
     * outcome is recorded but never announced as a failure.
     */
    data class Failed(val message: String?, val silent: Boolean = false) : ScheduleCompletion
}

/**
 * Reads a run's outcome off its transcript, or null while the turn is still in flight.
 *
 * The event stream is how a run normally settles, but it is a single long-lived connection: a doze
 * window, a runtime restart or a dropped socket swallows the `session.idle` that ends the run, and
 * the run then failed on the completion timeout long after the agent had actually answered.
 * Polling the transcript settles those runs from the same signal the chat already trusts - the
 * newest message being a finished assistant reply.
 */
fun scheduleCompletionOf(messages: List<OpenCodeMessage>): ScheduleCompletion? {
    val newest = messages.lastOrNull()?.info ?: return null
    // The prompt itself is the newest message until the agent starts replying.
    if (newest.role == "user") return null
    val error = newest.error
    return when {
        error != null -> ScheduleCompletion.Failed(error.message ?: error.name, silent = error.isAbort)
        newest.time.completed != null -> ScheduleCompletion.Completed
        else -> null
    }
}
