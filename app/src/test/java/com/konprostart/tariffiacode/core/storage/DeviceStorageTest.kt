package com.konprostart.tariffiacode.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DeviceStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun mounts(): DeviceStorage.Mounts =
        DeviceStorage.Mounts(
            sharedStorage = temporaryFolder.newFolder("emulated", "0"),
            volumes = temporaryFolder.newFolder("storage"),
        )

    @Test
    fun `an ungranted device contributes no mounts, so the sandbox is unchanged`() {
        assertTrue(DeviceStorage.Mounts.None.isEmpty)
        assertEquals(emptyList<String>(), DeviceStorage.bindArguments(DeviceStorage.Mounts.None, emptyList()))
        assertEquals(emptyList<String>(), DeviceStorage.guestRoots(DeviceStorage.Mounts.None))
    }

    @Test
    fun `only a registered project folder is bound, never the whole storage tree`() {
        val mounts = mounts()
        val project = File(mounts.sharedStorage, "Download/repo").apply { mkdirs() }

        assertEquals(
            listOf("-b", "${project.absolutePath}:/sdcard/Download/repo"),
            DeviceStorage.bindArguments(mounts, listOf("/sdcard/Download/repo")),
        )
    }

    @Test
    fun `a storage root cannot be registered to reopen broad access`() {
        assertEquals(emptyList<String>(), DeviceStorage.bindArguments(mounts(), listOf("/sdcard", "/storage")))
    }

    @Test
    fun `a path that is not a registered device project is not bound`() {
        assertEquals(emptyList<String>(), DeviceStorage.bindArguments(mounts(), listOf("/workspace/app")))
        assertEquals(emptyList<String>(), DeviceStorage.bindArguments(DeviceStorage.Mounts.None, listOf("/sdcard/Download/repo")))
    }

    @Test
    fun `only the storage mounts are device paths`() {
        assertTrue(DeviceStorage.isDeviceStoragePath("/sdcard"))
        assertTrue(DeviceStorage.isDeviceStoragePath("/sdcard/Download/notes"))
        assertTrue(DeviceStorage.isDeviceStoragePath("/storage/1A2B-3C4D/repo"))
        assertFalse(DeviceStorage.isDeviceStoragePath("/workspace/app"))
        assertFalse(DeviceStorage.isDeviceStoragePath("/root/.cache"))
        // A rootfs directory whose name merely starts the same way is not the mount.
        assertFalse(DeviceStorage.isDeviceStoragePath("/sdcardish"))
    }

    @Test
    fun `a guest path resolves to the host directory behind its mount`() {
        val mounts = mounts()
        File(mounts.sharedStorage, "Download/repo").mkdirs()

        assertEquals(
            File(mounts.sharedStorage, "Download/repo").canonicalFile,
            DeviceStorage.hostDirectory(mounts, "/sdcard/Download/repo")?.canonicalFile,
        )
        assertEquals(
            mounts.sharedStorage!!.canonicalFile,
            DeviceStorage.hostDirectory(mounts, "/sdcard")?.canonicalFile,
        )
        assertEquals(
            mounts.volumes!!.canonicalFile,
            DeviceStorage.hostDirectory(mounts, "/storage")?.canonicalFile,
        )
    }

    @Test
    fun `a path that climbs out of its mount resolves to nothing`() {
        assertNull(DeviceStorage.hostDirectory(mounts(), "/sdcard/../../etc"))
    }

    @Test
    fun `device paths resolve to nothing while access is ungranted`() {
        assertNull(DeviceStorage.hostDirectory(DeviceStorage.Mounts.None, "/sdcard/Download"))
        assertNull(DeviceStorage.hostDirectory(DeviceStorage.Mounts.None, "/storage"))
    }

    @Test
    fun `a path outside the mounts is left to the caller to resolve`() {
        assertNull(DeviceStorage.hostDirectory(mounts(), "/workspace/app"))
    }

    /**
     * Every sandbox launch asks again, so a grant made while the app is running takes effect on the
     * next start rather than on the next cold boot.
     */
    @Test
    fun `the installed provider is read on each call`() {
        val granted = mounts()
        var current = DeviceStorage.Mounts.None
        var projects = emptyList<String>()
        DeviceStorage.install { current }
        DeviceStorage.installProjectPaths { projects }
        try {
            assertEquals(emptyList<String>(), DeviceStorage.bindArguments())

            current = granted
            projects = listOf("/sdcard/Download/repo")
            File(granted.sharedStorage, "Download/repo").mkdirs()
            assertEquals(DeviceStorage.bindArguments(granted, projects), DeviceStorage.bindArguments())
        } finally {
            DeviceStorage.install { DeviceStorage.Mounts.None }
            DeviceStorage.installProjectPaths { emptyList() }
        }
    }
}
