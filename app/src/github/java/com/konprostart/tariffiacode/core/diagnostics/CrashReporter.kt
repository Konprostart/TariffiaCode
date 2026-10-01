package com.konprostart.tariffiacode.core.diagnostics

/**
 * No-op diagnostics reporter.
 *
 * Crash reporting beyond the local `CrashLog` file is intentionally not wired to any remote
 * service, so the app reports crashes nowhere off-device; they are only visible through the
 * device's own logs and the local crash report.
 */
object CrashReporter {
    fun install() = Unit

    fun log(message: String) = Unit

    fun recordException(
        error: Throwable,
        message: String? = null,
        customKeys: Map<String, String> = emptyMap(),
    ) = Unit
}
