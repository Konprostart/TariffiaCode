package com.konprostart.tariffiacode.feature.ssh

import com.konprostart.tariffiacode.data.ssh.SshProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshProfileValidatorTest {
    private fun valid() =
        SshProfile(
            name = "VPS",
            host = "example.com",
            port = 22,
            username = "root",
            credentialRef = "ssh-cred-1",
        )

    @Test
    fun `valid profile has no errors`() {
        assertTrue(SshProfileValidator.validate(valid()).isEmpty())
    }

    @Test
    fun `missing fields are reported`() {
        val errors =
            SshProfileValidator.validate(
                SshProfile(name = " ", host = "", port = 22, username = "", credentialRef = ""),
            )
        assertEquals(setOf("name", "host", "username", "credential"), errors.keys)
    }

    @Test
    fun `invalid port is reported`() {
        assertTrue(SshProfileValidator.validate(valid().copy(port = 0)).containsKey(SshProfileValidator.FIELD_PORT))
        assertTrue(SshProfileValidator.validate(valid().copy(port = 70000)).containsKey(SshProfileValidator.FIELD_PORT))
    }

    @Test
    fun `url pasted into host is rejected`() {
        assertFalse(SshProfileValidator.isValidHost("https://example.com"))
        assertFalse(SshProfileValidator.isValidHost("example.com/path"))
        assertFalse(SshProfileValidator.isValidHost("has space"))
    }

    @Test
    fun `hostnames ipv4 and ipv6 are accepted`() {
        assertTrue(SshProfileValidator.isValidHost("example.com"))
        assertTrue(SshProfileValidator.isValidHost("192.168.1.10"))
        assertTrue(SshProfileValidator.isValidHost("2001:db8::1"))
        assertTrue(SshProfileValidator.isValidHost("[2001:db8::1]"))
    }
}
