package com.konprostart.tariffiacode.core.ssh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshFailureDiagnosticTest {
    @Test
    fun `redacts password from summary and nested causes`() {
        val password = "private-password"
        val cause = IllegalStateException("authentication rejected $password", IllegalArgumentException("root $password"))

        val message = SshFailureDiagnostic.format("auth() failed for $password", cause, SshAuth.Password("user", password))

        assertTrue(message.contains("auth() failed for ***"))
        assertTrue(message.contains("java.lang.IllegalArgumentException: root ***"))
        assertFalse(message.contains(password))
    }

    @Test
    fun `redacts private key and passphrase from nested causes`() {
        val keyPem = "private-key-material"
        val passphrase = "private-key-passphrase"
        val cause = IllegalStateException("key=$keyPem passphrase=$passphrase")

        val message =
            SshFailureDiagnostic.format(
                "auth() failed",
                cause,
                SshAuth.PrivateKey("user", keyPem, passphrase),
            )

        assertTrue(message.contains("key=*** passphrase=***"))
        assertFalse(message.contains(keyPem))
        assertFalse(message.contains(passphrase))
    }
}
