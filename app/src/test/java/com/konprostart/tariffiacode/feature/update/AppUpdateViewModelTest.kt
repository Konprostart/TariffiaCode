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
import java.security.MessageDigest

class AppUpdateViewModelTest {
    private val sha = "a".repeat(64)

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
        val downloads = mutableListOf<Triple<String, File, String>>()

        override suspend fun download(
            apkUrl: String,
            destination: File,
            expectedSha256: String,
            expectedSizeBytes: Long?,
        ) {
            if (fail) error("download failed")
            downloads += Triple(apkUrl, destination, expectedSha256)
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

    private val manifests = mutableMapOf<String, ByteArray>()

    private fun releaseJson(tag: String = "v9.9.9"): String {
        val assetName = "tariffiacode-$tag-release.apk"
        val manifestName = "tariffiacode-$tag-update.json"
        val manifestUrl = "https://github.com/Konprostart/TariffiaCode/releases/download/$tag/$manifestName"
        val manifest =
            """{"schemaVersion":1,"channel":"release","applicationId":"com.konprostart.tariffiacode","versionName":"${tag.removePrefix(
                "v",
            )}","versionCode":9999,"commitSha":"${"c".repeat(
                40,
            )}","apkAssetName":"$assetName","apkSha256":"$sha","signerSha256":"${"b".repeat(64)}"}"""
        val manifestBytes = manifest.toByteArray()
        val manifestSha = MessageDigest.getInstance("SHA-256").digest(manifestBytes).joinToString("") { "%02x".format(it) }
        manifests[manifestUrl] = manifestBytes
        return """[{"tag_name":"$tag","draft":false,"prerelease":false,"assets":[{"name":"$assetName","browser_download_url":"https://github.com/Konprostart/TariffiaCode/releases/download/$tag/$assetName","digest":"sha256:$sha","size":12345},{"name":"$manifestName","browser_download_url":"$manifestUrl","digest":"sha256:$manifestSha"}]}]"""
    }

    private fun release(
        url: String = "https://example.com/update.apk",
        sha: String = this.sha,
    ) = AppUpdateRelease(version = "9.9.9", apkUrl = url, sha256 = sha)

    private fun viewModel(
        json: String,
        downloader: AppUpdateApkDownloader,
        installer: AppUpdateInstaller,
        apkFile: File,
    ) = AppUpdateViewModel(
        installedVersion = "1.2.29",
        client =
            AppUpdateReleaseClient(
                fetchRelease = { json },
                fetchAsset = { url -> manifests.getValue(url.substringBefore('?')) },
            ),
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

            vm.downloadAndInstall(release("https://example.com/update.apk", sha))

            // The expected hash from the release is what the downloader verifies against.
            assertEquals(listOf(Triple("https://example.com/update.apk", apkFile, sha)), downloader.downloads)
            assertEquals(listOf(apkFile), installer.installed)
            assertFalse(vm.state.value.isDownloading)
            assertNotNull(vm.state.value.installMessage)
        }

    @Test
    fun `a download failure is surfaced and nothing is installed`() =
        runTest {
            val installer = FakeInstaller()
            val vm = viewModel(releaseJson(), FakeDownloader(fail = true), installer, File.createTempFile("apk", ".apk"))

            vm.downloadAndInstall(release())

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

            vm.downloadAndInstall(release())

            assertTrue(installer.permissionRequested)
            assertTrue(downloader.downloads.isEmpty())
            assertTrue(installer.installed.isEmpty())
            assertNotNull(vm.state.value.installMessage)
        }
}
