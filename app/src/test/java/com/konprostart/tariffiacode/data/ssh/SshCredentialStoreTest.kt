package com.konprostart.tariffiacode.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshCredentialStoreTest {
    private class FakeBackend {
        var entries: Map<String, String> = emptyMap()
    }

    private fun storeOn(backend: FakeBackend) = SshCredentialStore(load = { backend.entries }, save = { backend.entries = it })

    @Test
    fun `password and private key are stored separately and read back`() {
        val backend = FakeBackend()
        val store = storeOn(backend)

        store.setCredential("ref-a", SshCredential.Password("hunter2"))
        store.setCredential("ref-b", SshCredential.PrivateKey("-----BEGIN KEY-----", "phrase"))

        assertEquals(SshCredential.Password("hunter2"), store.credential("ref-a"))
        assertEquals(SshCredential.PrivateKey("-----BEGIN KEY-----", "phrase"), store.credential("ref-b"))
        assertEquals(setOf("ref-a", "ref-b"), store.credentialRefs())
    }

    @Test
    fun `credential reference list never exposes the secrets`() {
        val backend = FakeBackend()
        val store = storeOn(backend)
        store.setCredential("ref-a", SshCredential.Password("hunter2"))

        // The persisted map keys are references; the values are encrypted by the backing store in
        // production, and here we only assert the API never returns raw secret maps.
        assertTrue(store.credentialRefs().all { !it.contains("hunter2") })
        assertFalse(store.credentialRefs().any { it.contains("hunter2") })
    }

    @Test
    fun `blank credential is rejected`() {
        val store = storeOn(FakeBackend())
        val rejected =
            runCatching { store.setCredential("ref", SshCredential.Password("")) }.isFailure
        assertTrue(rejected)
    }

    @Test
    fun `clearing a credential removes it`() {
        val backend = FakeBackend()
        val store = storeOn(backend)
        store.setCredential("ref", SshCredential.Password("secret"))
        assertTrue(store.hasCredential("ref"))

        store.clearCredential("ref")

        assertFalse(store.hasCredential("ref"))
        assertTrue(store.credentialRefs().isEmpty())
    }

    @Test
    fun `toString on credentials never reveals material`() {
        assertFalse(SshCredential.Password("hunter2-secret").toString().contains("hunter2-secret"))
        assertFalse(SshCredential.PrivateKey("PEM-SECRET-DATA", "PASSPHRASE-SECRET").toString().contains("PEM-SECRET-DATA"))
        assertFalse(SshCredential.PrivateKey("PEM-SECRET-DATA", "PASSPHRASE-SECRET").toString().contains("PASSPHRASE-SECRET"))
    }
}
