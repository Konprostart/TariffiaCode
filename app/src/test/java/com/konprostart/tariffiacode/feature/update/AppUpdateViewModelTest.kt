package com.konprostart.tariffiacode.feature.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class AppUpdateViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeDownloader(
        var fail: Boolean = false,
    ) : AppUpdateApkDownloader {
        val downloads = mutableListOf<Pair<String, File>>()

        override suspend fun download(
            apkUrl: String,
            destination: File,
        ) {
            if (fail) error("download failed")
            downloads += apkUrl to destination
        }
    }

    private class FakeInstaller(
        var canInstall: Boolean = true,
    ) : AppUpdateInstaller {
        val installed = mutableListOf<File>()
        var permissionRequested = false

        override fun canInstall(): Boolean = canInstall

        override fun install(apk: File) {
            installed += apk
        }

        override fun requestInstallPermission() {
            permissionRequested = true
        }
    }

    private fun releaseJson(tag: String = "v9.9.9"): String =
        """
        [
          {
            "tag_name": "$tag",
            "draft": false,
            "prerelease": false,
            "assets": [
              {
                "name": "tariffiacode-$tag-release.apk",
                "browser_download_url": "https://github.com/Konprostart/TariffiaCode/releases/download/$tag/tariffiacode-$tag-release.apk"
              }
            ]
          }
        ]
        """.trimIndent()

    private fun viewModel(
        json: String,
        downloader: AppUpdateApkDownloader,
        installer: AppUpdateInstaller,
        apkFile: File,
    ) = AppUpdateViewModel(
        installedVersion = "1.2.29",
        client = AppUpdateReleaseClient(fetchRelease = { json }),
        downloader = downloader,
        installer = installer,
        apkFileProvider = { apkFile },
    )

    @Test
    fun `update available is detected from the release payload`() =
        runTest {
            val vm = viewModel(releaseJson(), FakeDownloader(), FakeInstaller(), File.createTempFile("apk", ".apk"))

            vm.checkForUpdate()

            val check = vm.state.value.check
            assertTrue(check is AppUpdateCheck.Available)
            assertEquals("9.9.9", (check as AppUpdateCheck.Available).release.version)
            assertTrue(check.release.apkUrl.endsWith("tariffiacode-v9.9.9-release.apk?download=1"))
        }

    @Test
    fun `no update available when the release is not newer`() =
        runTest {
            val vm = viewModel(releaseJson(tag = "v1.0.0"), FakeDownloader(), FakeInstaller(), File.createTempFile("apk", ".apk"))

            vm.checkForUpdate()

            assertTrue(vm.state.value.check is AppUpdateCheck.UpToDate)
        }

    @Test
    fun `download and install hands the downloaded APK to the installer`() =
        runTest {
            val downloader = FakeDownloader()
            val installer = FakeInstaller()
            val apkFile = File.createTempFile("apk", ".apk")
            val vm = viewModel(releaseJson(), downloader, installer, apkFile)

            vm.downloadAndInstall("https://example.com/update.apk")

            assertEquals(listOf("https://example.com/update.apk" to apkFile), downloader.downloads)
            assertEquals(listOf(apkFile), installer.installed)
            assertFalse(vm.state.value.isDownloading)
            assertNotNull(vm.state.value.installMessage)
        }

    @Test
    fun `a download failure is surfaced and nothing is installed`() =
        runTest {
            val installer = FakeInstaller()
            val vm = viewModel(releaseJson(), FakeDownloader(fail = true), installer, File.createTempFile("apk", ".apk"))

            vm.downloadAndInstall("https://example.com/update.apk")

            assertNotNull(vm.state.value.error)
            assertTrue(installer.installed.isEmpty())
            assertFalse(vm.state.value.isDownloading)
        }

    @Test
    fun `without install permission the user is sent to the system screen and no download happens`() =
        runTest {
            val downloader = FakeDownloader()
            val installer = FakeInstaller(canInstall = false)
            val vm = viewModel(releaseJson(), downloader, installer, File.createTempFile("apk", ".apk"))

            vm.downloadAndInstall("https://example.com/update.apk")

            assertTrue(installer.permissionRequested)
            assertTrue(downloader.downloads.isEmpty())
            assertTrue(installer.installed.isEmpty())
            assertNotNull(vm.state.value.installMessage)
        }
}
