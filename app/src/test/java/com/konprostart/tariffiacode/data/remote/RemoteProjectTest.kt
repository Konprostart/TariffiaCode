package com.konprostart.tariffiacode.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteProjectTest {
    private class Backend {
        var projects: List<RemoteProject> = emptyList()
        var sshIds: Set<String> = setOf("vps-1")
    }

    private fun store(backend: Backend): RemoteProjectStore =
        RemoteProjectStore(
            load = { backend.projects },
            save = { backend.projects = it },
            sshProfileExists = { it in backend.sshIds },
        )

    private fun mapping(
        id: String = "m1",
        projectRef: String = "Konprostart/TariffiaCode",
        sshProfileId: String = "vps-1",
        remotePath: String = "/root/projects/TariffiaCode",
        displayName: String = "",
    ) = RemoteProject(
        id = id,
        displayName = displayName,
        projectRef = projectRef,
        sshProfileId = sshProfileId,
        remotePath = remotePath,
    )

    @Test
    fun `valid mapping saves and loads`() {
        val backend = Backend()
        val store = store(backend)

        val errors = store.upsert(mapping())

        assertTrue(errors.isEmpty())
        assertEquals(listOf("m1"), store.projects().map { it.id })
        assertEquals("Konprostart/TariffiaCode", store.project("m1")?.projectRef)
    }

    @Test
    fun `round trips through the codec`() {
        val original = listOf(mapping(displayName = "My project"))
        assertEquals(original, RemoteProjectCodec.decode(RemoteProjectCodec.encode(original)))
    }

    @Test
    fun `missing project reference is rejected`() {
        val errors = store(Backend()).upsert(mapping(projectRef = " "))
        assertTrue(errors.containsKey(RemoteProjectStore.FIELD_PROJECT))
    }

    @Test
    fun `unknown ssh profile is rejected`() {
        val errors = store(Backend()).upsert(mapping(sshProfileId = "missing"))
        assertEquals("unknown", errors[RemoteProjectStore.FIELD_SSH_PROFILE])
    }

    @Test
    fun `non absolute or traversing remote path is rejected`() {
        val store = store(Backend())
        assertTrue(store.upsert(mapping(remotePath = "root/projects")).containsKey(RemoteProjectStore.FIELD_REMOTE_PATH))
        assertTrue(store.upsert(mapping(remotePath = "/root/../etc")).containsKey(RemoteProjectStore.FIELD_REMOTE_PATH))
        assertTrue(store.upsert(mapping(remotePath = "  ")).containsKey(RemoteProjectStore.FIELD_REMOTE_PATH))
    }

    @Test
    fun `absolute path helper`() {
        assertTrue(RemoteProjectStore.isAbsolutePath("/root/app"))
        assertFalse(RemoteProjectStore.isAbsolutePath("root/app"))
        assertFalse(RemoteProjectStore.isAbsolutePath("/root/../app"))
    }

    @Test
    fun `deleting an ssh profile leaves a dangling mapping that is detected and removable`() {
        val backend = Backend()
        val store = store(backend)
        store.upsert(mapping())
        assertEquals(0, store.danglingProjects().size)

        // The referenced SSH profile disappears.
        backend.sshIds = emptySet()

        assertEquals(listOf("m1"), store.danglingProjects().map { it.id })
        assertFalse(store.isResolvable("m1"))
        val removed = store.removeDangling()
        assertEquals(listOf("m1"), removed.map { it.id })
        assertTrue(store.projects().isEmpty())
    }

    @Test
    fun `mapping holds no secret fields`() {
        // The model only carries ids/paths; the referenced profile owns the credential.
        val text = mapping().toString()
        assertFalse(text.contains("password", ignoreCase = true))
        assertFalse(text.contains("keyPem", ignoreCase = true))
        assertFalse(text.contains("passphrase", ignoreCase = true))
    }

    @Test
    fun `resolver uses an explicit directory first`() {
        assertEquals(
            "/explicit",
            RemoteProjectResolver.resolveDirectory(mapping(), "vps-1", "/explicit"),
        )
    }

    @Test
    fun `resolver falls back to the mapped path for the selected profile`() {
        assertEquals(
            "/root/projects/TariffiaCode",
            RemoteProjectResolver.resolveDirectory(mapping(), "vps-1", null),
        )
    }

    @Test
    fun `resolver ignores a mapping for a different ssh profile`() {
        assertNull(RemoteProjectResolver.resolveDirectory(mapping(), "other-vps", null))
        assertNull(RemoteProjectResolver.resolveDirectory(mapping(), null, null))
        assertNull(RemoteProjectResolver.resolveDirectory(null, "vps-1", null))
    }
}
