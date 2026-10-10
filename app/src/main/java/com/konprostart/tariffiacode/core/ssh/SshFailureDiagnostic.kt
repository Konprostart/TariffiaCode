package com.konprostart.tariffiacode.core.ssh

/** Formats SSH failures for display without exposing the authentication material. */
internal object SshFailureDiagnostic {
    fun format(
        summary: String,
        cause: Throwable?,
        auth: SshAuth?,
    ): String {
        val secrets =
            when (auth) {
                is SshAuth.Password -> listOf(auth.password)
                is SshAuth.PrivateKey -> listOf(auth.keyPem, auth.passphrase.orEmpty())
                null -> emptyList()
            }.filter(String::isNotBlank).distinct().sortedByDescending(String::length)

        fun redact(value: String?): String = secrets.fold(value.orEmpty()) { text, secret -> text.replace(secret, "***") }

        val safeSummary = redact(summary)
        if (cause == null) return safeSummary

        val chain =
            generateSequence(cause) { it.cause }
                .take(MAX_CAUSES)
                .joinToString("\n") { error ->
                    val detail = error.message?.let { ": ${redact(it)}" }.orEmpty()
                    "  ${error.javaClass.name}$detail"
                }
        return "$safeSummary\nCause chain:\n$chain"
    }

    private const val MAX_CAUSES = 8
}
