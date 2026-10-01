package com.konprostart.tariffiacode.core.diagnostics

/**
 * No-op diagnostics reporter.
 *
 * This build collects no usage analytics at all rather than sending them anywhere else.
 */
object AnalyticsReporter {
    fun install(
        context: android.content.Context,
        enabled: Boolean = false,
    ) = Unit

    fun setEnabled(enabled: Boolean) = Unit

    fun recordRuntimeSessionCompleted() = Unit

    fun recordRuntimeSessionError() = Unit

    fun recordRuntimeSessionStalled(reason: StallReason) = Unit
}
