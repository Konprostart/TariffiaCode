package com.konprostart.tariffiacode.data.ssh

import org.junit.Assert.assertEquals
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
    fun `profile renders only a credential reference, never a secret`() {
        val profile = SshProfile(name = "n", host = "h", username = "u", credentialRef = "ref-123")
        // The model carries a reference, not a secret, and its string form exposes exactly that.
        assertTrue(profile.toString().contains("credentialRef=ref-123"))
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
