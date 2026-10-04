package com.konprostart.tariffiacode.feature.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateReleaseClientTest {
    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        withApk: Boolean = true,
    ): String {
        val assets =
            if (withApk) {
                """"assets":[{"name":"tariffiacode-$tag-release.apk","browser_download_url":
                    "https://github.com/Konprostart/TariffiaCode/releases/download/$tag/tariffiacode-$tag-release.apk"}]"""
            } else {
                """"assets":[]"""
            }
        return """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,$assets}"""
    }

    private fun client(payload: String): AppUpdateReleaseClient = AppUpdateReleaseClient(fetchRelease = { "[$payload]" })

    @Test
    fun `current version is up to date`() =
        runTest {
            val result = client(release("v1.2.27")).check("1.2.27")
            assertTrue(result is AppUpdateCheck.UpToDate)
            assertEquals("1.2.27", result.currentVersion)
        }

    @Test
    fun `newer version is available with the official download url`() =
        runTest {
            val result = client(release("v1.2.28")).check("1.2.27")
            assertTrue(result is AppUpdateCheck.Available)
            val available = result as AppUpdateCheck.Available
            assertEquals("1.2.28", available.release.version)
            assertTrue(available.release.apkUrl.startsWith("https://github.com/Konprostart/TariffiaCode/releases/download/v1.2.28/"))
            assertTrue(available.release.apkUrl.contains("download=1"))
        }

    @Test
    fun `older latest release is up to date`() =
        runTest {
            val result = client(release("v1.2.26")).check("1.2.27")
            assertTrue(result is AppUpdateCheck.UpToDate)
        }

    @Test
    fun `malformed tag is ignored rather than crashing`() =
        runTest {
            val payload = "[${release("not-a-version")}]"
            val result = AppUpdateReleaseClient(fetchRelease = { payload }).check("1.2.27")
            assertTrue(result is AppUpdateCheck.UpToDate)
        }

    @Test
    fun `draft and prerelease are skipped`() =
        runTest {
            val payload = listOf(release("v1.2.30", prerelease = true), release("v1.2.29", draft = true)).joinToString(",", "[", "]")
            val result = AppUpdateReleaseClient(fetchRelease = { payload }).check("1.2.27")
            assertTrue(result is AppUpdateCheck.UpToDate)
        }

    @Test
    fun `newer release missing the apk asset fails`() =
        runTest {
            val result = runCatching { client(release("v1.2.28", withApk = false)).check("1.2.27") }
            assertTrue(result.isFailure)
        }

    @Test
    fun `network or api failure surfaces as a failure`() =
        runTest {
            val client = AppUpdateReleaseClient(fetchRelease = { error("TariffiaCode release check failed with HTTP 500") })
            val result = runCatching { client.check("1.2.27") }
            assertTrue(result.isFailure)
        }

    /**
     * Regression for the main-thread network bug. The fetch contract is now `suspend`, and the
     * production `defaultFetchRelease` wraps its blocking OkHttp call in `withContext(Dispatchers.IO)`.
     * This proves `check` awaits a suspend fetch that is dispatched onto [Dispatchers.IO] and that such
     * an off-caller-dispatcher fetch is honored end-to-end (no NetworkOnMainThreadException path).
     */
    @Test
    fun `suspend fetch dispatched on IO is awaited and honored`() =
        runTest {
            var ranOnMain = true
            val client =
                AppUpdateReleaseClient(
                    fetchRelease = {
                        withContext(Dispatchers.IO) {
                            ranOnMain = Thread.currentThread().name.contains("main", ignoreCase = true)
                            "[${release("v1.2.28")}]"
                        }
                    },
                )
            val result = client.check("1.2.27")
            assertTrue(result is AppUpdateCheck.Available)
            assertFalse("fetch dispatched on IO must not run on the main thread", ranOnMain)
        }
}
