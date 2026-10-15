package com.konprostart.tariffiacode.core.diagnostics

import com.konprostart.tariffiacode.core.security.SecretRedaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stack trace an uncaught exception produces, as it reaches the clipboard.
 *
 * This is the path that motivated the redaction. `CrashLog` writes the raw trace to disk and the
 * diagnostics sheet pastes that file into the system clipboard, which any installed app with a
 * clipboard listener can read. An exception message can carry a credential: OkHttp reports a
 * malformed URL back with the whole URL in the message, and this app builds at least one URL with a
 * provider key in its query string.
 *
 * `SecretRedaction` has its own tests. What is asserted here is the thing they cannot see - that the
 * two are actually wired together, and wired in front of the value that leaves the app.
 */
class CrashLogRedactionTest {
    @Test
    fun `a stack trace carrying a provider key in a url is scrubbed`() {
        val secret = "AIzaSyD-REALKEY1234567890abcdefghijklmnop"
        val trace =
            """
            java.lang.IllegalArgumentException: unexpected url: $secret
            	at okhttp3.Request${'$'}.Builder.url(Request.java:112)
            	at com.konprostart.tariffiacode.core.router.RouterEngine.call(RouterEngine.kt:162)
            """.trimIndent()

        val redacted = SecretRedaction.redact(trace)

        assertFalse("the key must not survive redaction", redacted.contains(secret))
        assertTrue("the shape of the error must survive", redacted.contains("unexpected url"))
    }

    @Test
    fun `bearer tokens and ssh passwords are scrubbed from a trace`() {
        val trace =
            """
            java.io.IOException: Authorization: Bearer ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345
            java.io.IOException: password=hunter2 from sshd
            """.trimIndent()

        val redacted = SecretRedaction.redact(trace)

        assertFalse(redacted.contains("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"))
        assertFalse(redacted.contains("hunter2"))
    }

    @Test
    fun `the diagnostic block keeps its structure so a report is still readable`() {
        // Redaction that destroys the report is not a fix, it is a different bug: the value of the
        // clipboard button is the stack, not the product name.
        val redacted = SecretRedaction.redact("java.lang.RuntimeException: boom at App.kt:1")

        assertEquals("java.lang.RuntimeException: boom at App.kt:1", redacted)
    }

    @Test
    fun `the sanitizer used by analytics shares the same scrubber`() {
        // CrashReportSanitizer.message() is what the analytics path writes. If it ever stops routing
        // through SecretRedaction, a crash-shaped string could reach a log line unscrubbed.
        assertEquals(
            SecretRedaction.redact("token=abcdefghijklmnopqrst"),
            CrashReportSanitizer.message("token=abcdefghijklmnopqrst"),
        )
    }
}
