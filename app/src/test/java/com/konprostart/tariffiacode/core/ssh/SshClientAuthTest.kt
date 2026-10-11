package com.konprostart.tariffiacode.core.ssh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshClientAuthTest {
    @Test
    fun `replies once per keyboard-interactive prompt`() {
        val responder = PasswordKeyboardInteractive("s3cret")
        assertArrayEquals(arrayOf("s3cret"), responder.repliesFor(1))
        assertArrayEquals(arrayOf("s3cret", "s3cret"), responder.repliesFor(2))
        assertTrue(responder.repliesFor(0).isEmpty())
    }

    @Test
    fun `toString never reveals the password`() {
        assertFalse(PasswordKeyboardInteractive("s3cret").toString().contains("s3cret"))
    }
}
