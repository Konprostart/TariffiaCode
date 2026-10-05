package com.konprostart.tariffiacode.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshProfileStoreTest {
    private class FakeProfiles {
        var list: List<SshProfile> = emptyList()
    }

    private class FakeCreds {
        var entries: Map<String, String> = emptyMap()
    }

    private fun fixture(): Triple<SshProfileStore, FakeProfiles, FakeCreds> {
        val profiles = FakeProfiles()
        val creds = FakeCreds()
        val credentialStore = SshCredentialStore(load = { creds.entries }, save = { creds.entries = it })
        val store =
            SshProfileStore(
                load = { profiles.list },
                save = { profiles.list = it },
                credentials = credentialStore,
            )
        return Triple(store, profiles, creds)
    }

    @Test
    fun `upsert inserts then replaces preserving order`() {
        val (store, _, _) = fixture()
        val a = SshProfile(id = "a", name = "A", host = "h", username = "u")
        val b = SshProfile(id = "b", name = "B", host = "h", username = "u")

        store.upsert(a)
        store.upsert(b)
        store.upsert(a.copy(name = "A2"))

        assertEquals(listOf("a", "b"), store.profiles().map { it.id })
        assertEquals("A2", store.profile("a")?.name)
    }

    @Test
    fun `delete removes the profile and its stored secret`() {
        val (store, _, credsBackend) = fixture()
        val credentialStore =
            SshCredentialStore(load = { credsBackend.entries }, save = { credsBackend.entries = it })
        val profile = SshProfile(id = "a", name = "A", host = "h", username = "u")
        credentialStore.setCredential(profile.credentialRef, SshCredential.Password("secret"))
        store.upsert(profile)

        store.delete("a")

        assertNull(store.profile("a"))
        assertTrue(store.profiles().isEmpty())
        assertFalse(credentialStore.hasCredential(profile.credentialRef))
    }
}
