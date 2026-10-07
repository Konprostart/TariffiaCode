package com.konprostart.tariffiacode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalRuntimeServerSecretTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `read is null before the server has ever started`() {
        assertNull(LocalRuntimeServerSecret.read(temporaryFolder.newFolder("runtime")))
    }

    @Test
    fun `a restarted server gets a new secret and read returns the latest`() {
        val runtimeDirectory = temporaryFolder.newFolder("runtime")

        val first = LocalRuntimeServerSecret.rotate(runtimeDirectory)
        val second = LocalRuntimeServerSecret.rotate(runtimeDirectory)

        assertNotEquals(first, second)
        assertEquals(second, LocalRuntimeServerSecret.read(runtimeDirectory))
    }

    @Test
    fun `the secret is a 256-bit hex value`() {
        val secret = LocalRuntimeServerSecret.newSecret()
        assertEquals(64, secret.length)
        assertTrue(secret.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `the secret is stored outside the guest rootfs and workspace`() {
        val runtimeDirectory = temporaryFolder.newFolder("runtime")
        val rootfs = File(runtimeDirectory, "environment/rootfs").apply { mkdirs() }
        val workspace = File(runtimeDirectory, "workspace").apply { mkdirs() }

        val secret = LocalRuntimeServerSecret.rotate(runtimeDirectory)

        // Written in the app-private runtime dir...
        assertTrue(
            runtimeDirectory.listFiles().orEmpty().any { it.isFile && it.readText().trim() == secret },
        )
        // ...and nowhere the agent sandbox can reach.
        assertFalse(rootfs.walkTopDown().any { it.isFile && it.readText().contains(secret) })
        assertFalse(workspace.walkTopDown().any { it.isFile && it.readText().contains(secret) })
    }

    @Test
    fun `clear removes the secret`() {
        val runtimeDirectory = temporaryFolder.newFolder("runtime")
        LocalRuntimeServerSecret.rotate(runtimeDirectory)

        LocalRuntimeServerSecret.clear(runtimeDirectory)

        assertNull(LocalRuntimeServerSecret.read(runtimeDirectory))
    }
}
