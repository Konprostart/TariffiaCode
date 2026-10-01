package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AntigravityUpdateTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `the installer records the release it wrote`() {
        val rootfs = temporaryFolder.newFolder("rootfs")

        AntigravityInstaller.writeInstalledVersion(rootfs, "1.1.6")

        assertEquals("1.1.6", AntigravityInstaller.installedVersion(rootfs))
    }

    @Test
    fun `a sandbox without a marker reports no recorded version`() {
        assertNull(AntigravityInstaller.installedVersion(temporaryFolder.newFolder("bare-rootfs")))
    }

    @Test
    fun `an unknown installed release is always replaced`() {
        assertTrue(shouldReplaceInstalledAntigravity(currentVersion = null, latestVersion = "1.1.17"))
        assertTrue(shouldReplaceInstalledAntigravity(currentVersion = "", latestVersion = "1.1.17"))
        assertTrue(shouldReplaceInstalledAntigravity(currentVersion = "not-a-version", latestVersion = "1.1.17"))
    }

    @Test
    fun `an older or equal installed release is only replaced by something newer`() {
        assertFalse(shouldReplaceInstalledAntigravity(currentVersion = "1.1.17", latestVersion = "1.1.17"))
        assertFalse(shouldReplaceInstalledAntigravity(currentVersion = "1.2.0", latestVersion = "1.1.17"))
        assertTrue(shouldReplaceInstalledAntigravity(currentVersion = "1.1.7", latestVersion = "1.1.17"))
        assertTrue(shouldReplaceInstalledAntigravity(currentVersion = "v1.1.7", latestVersion = "1.1.8"))
    }

    @Test
    fun `an update reports both sides of the version change`() {
        assertEquals(
            AntigravityUpdateResult.Updated("1.1.6", "1.1.7"),
            antigravityUpdateResult(before = "1.1.6", after = "1.1.7"),
        )
        assertEquals(
            AntigravityUpdateResult.AlreadyLatest("1.1.7"),
            antigravityUpdateResult(before = "1.1.7", after = "1.1.7"),
        )
        assertEquals(
            AntigravityUpdateResult.AlreadyLatest("1.1.7"),
            antigravityUpdateResult(before = null, after = "1.1.7"),
        )
    }
}
