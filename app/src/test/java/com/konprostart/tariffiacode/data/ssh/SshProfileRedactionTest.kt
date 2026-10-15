package com.konprostart.tariffiacode.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one secret the profile model now carries.
 *
 * `SshProfile` existed on the rule that it holds no secret, so `toString()` rendered every field
 * verbatim. That rule no longer holds: the profile carries the `OPENCODE_SERVER_PASSWORD` its VPS
 * tunnel is authenticated with, and a rendered profile ends up in log lines and error messages.
 *
 * These assert the redaction holds, because the failure mode is a silent one - the format string
 * looks perfectly fine either way.
 */
class SshProfileRedactionTest {
    private val password = "super-secret-opencode-password"

    private val profile =
        SshProfile(
            name = "vps",
            host = "example.com",
            username = "root",
            opencodePassword = password,
        )

    @Test
    fun `a rendered profile never shows the opencode password`() {
        assertFalse(profile.toString().contains(password))
        assertTrue(profile.toString().contains("opencodePassword=<redacted>"))
    }

    @Test
    fun `a profile with no password says so rather than printing a placeholder`() {
        val without = profile.copy(opencodePassword = null)

        assertTrue(without.toString().contains("opencodePassword=<none>"))
    }

    @Test
    fun `trusting and forgetting a host key keep the password`() {
        // Both are ordinary copy() calls. If either were ever rebuilt field by field, this is what
        // would quietly drop the credential the VPS connection is authenticated with.
        assertEquals(password, profile.trusting("AA:BB").opencodePassword)
        assertEquals(password, profile.forgettingHostKey().opencodePassword)
    }

    @Test
    fun `the password survives a serialization round trip`() {
        val decoded = SshProfileCodec.decode(SshProfileCodec.encode(listOf(profile)))

        assertEquals(password, decoded.single().opencodePassword)
    }

    @Test
    fun `a profile saved before this field existed still decodes`() {
        // Existing installs have stored profiles with no such key. `ignoreUnknownKeys` covers the
        // other direction, but the missing-field direction has to produce null rather than fail, or
        // every saved VPS connection would be lost on upgrade.
        val withoutField =
            """[{"id":"a","name":"vps","host":"example.com","port":22,"username":"root",
               "authType":"password","credentialRef":"ssh-cred-1"}]"""

        val decoded = SshProfileCodec.decode(withoutField)

        assertEquals(null, decoded.single().opencodePassword)
    }
}
