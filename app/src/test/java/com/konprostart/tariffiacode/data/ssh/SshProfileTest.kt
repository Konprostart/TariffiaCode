package com.konprostart.tariffiacode.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshProfileTest {
    @Test
    fun `profiles round trip through json`() {
        val original =
            listOf(
                SshProfile(
                    id = "vps-1",
                    name = "VPS",
                    host = "10.0.0.5",
                    port = 2222,
                    username = "root",
                    authType = SshAuthType.PRIVATE_KEY,
                    credentialRef = "ssh-cred-1",
                    trustedHostKeyFingerprint = "ab".repeat(32),
                ),
            )

        val decoded = SshProfileCodec.decode(SshProfileCodec.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `default port is 22 and default auth is password`() {
        val profile = SshProfile(name = "n", host = "h", username = "u")
        assertEquals(SshProfile.DEFAULT_PORT, profile.port)
        assertEquals(SshAuthType.PASSWORD, profile.authType)
        assertNull(profile.trustedHostKeyFingerprint)
    }

    @Test
    fun `profile holds no secret and never renders one`() {
        val profile = SshProfile(name = "n", host = "h", username = "u")
        // The model has no password/key/passphrase field at all.
        assertFalse(profile.toString().contains("password", ignoreCase = true))
        assertFalse(profile.toString().contains("passphrase", ignoreCase = true))
    }

    @Test
    fun `trusting records and forgetting clears the fingerprint`() {
        val profile = SshProfile(name = "n", host = "h", username = "u")
        val trusted = profile.trusting("cd".repeat(32))
        assertEquals("cd".repeat(32), trusted.trustedHostKeyFingerprint)
        assertNull(trusted.forgettingHostKey().trustedHostKeyFingerprint)
    }

    @Test
    fun `blank json decodes to empty list`() {
        assertTrue(SshProfileCodec.decode("").isEmpty())
    }
}
