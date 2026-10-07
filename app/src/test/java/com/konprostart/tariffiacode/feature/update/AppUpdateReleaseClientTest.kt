package com.konprostart.tariffiacode.feature.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateReleaseClientTest {
    private val SHA = "a".repeat(64)

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        withApk: Boolean = true,
        digest: String? = "sha256:$SHA",
        size: Long? = 12_345L,
    ): String {
        val assets =
            if (withApk) {
                val digestJson = if (digest != null) ",\"digest\":\"$digest\"" else ""
                val sizeJson = if (size != null) ",\"size\":$size" else ""
                val asset =
                    "{\"name\":\"tariffiacode-$tag-release.apk\",\"browser_download_url\":" +
                        "\"https://github.com/Konprostart/TariffiaCode/releases/download/$tag/tariffiacode-$tag-release.apk\"" +
                        "$digestJson$sizeJson}"
                "\"assets\":[$asset]"
            } else {
                "\"assets\":[]"
            }
        return "{\"tag_name\":\"$tag\",\"draft\":$draft,\"prerelease\":$prerelease,$assets}"
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
            // The expected hash/size come from GitHub's own asset digest, not a hardcoded value.
            assertEquals(SHA, available.release.sha256)
            assertEquals(12_345L, available.release.sizeBytes)
        }

    @Test
    fun `an update without a sha256 digest is refused rather than accepted unverified`() =
        runTest {
            val result = runCatching { client(release("v1.2.28", digest = null)).check("1.2.27") }
            assertTrue("an asset with no digest must not be offered for install", result.isFailure)
        }

    @Test
    fun `an update with a malformed or non-sha256 digest is refused`() =
        runTest {
            assertTrue(runCatching { client(release("v1.2.28", digest = "sha256:not-a-hash")).check("1.2.27") }.isFailure)
            assertTrue(runCatching { client(release("v1.2.28", digest = "md5:$SHA")).check("1.2.27") }.isFailure)
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

    // ---- Debug channel ----
    // The debug channel reads the single object returned by `/releases/tags/debug-latest`; the
    // rolling tag carries no version, so the version comes from the release `name`.

    private fun debugRelease(
        version: String,
        assetName: String = "tariffiacode-debug.apk",
        withAsset: Boolean = true,
        digest: String? = "sha256:$SHA",
    ): String {
        val assets =
            if (withAsset) {
                val digestJson = if (digest != null) ",\"digest\":\"$digest\"" else ""
                val asset =
                    "{\"name\":\"$assetName\",\"browser_download_url\":" +
                        "\"https://github.com/Konprostart/TariffiaCode/releases/download/debug-latest/$assetName\"$digestJson}"
                "\"assets\":[$asset]"
            } else {
                "\"assets\":[]"
            }
        return "{\"tag_name\":\"debug-latest\",\"name\":\"$version\",\"draft\":false,\"prerelease\":true,$assets}"
    }

    private fun debugClient(payload: String): AppUpdateReleaseClient =
        AppUpdateReleaseClient(channel = AppUpdateChannel.Debug, fetchRelease = { payload })

    @Test
    fun `debug channel offers a newer debug update`() =
        runTest {
            val result = debugClient(debugRelease("1.2.30")).check("1.2.29")
            assertTrue(result is AppUpdateCheck.Available)
            val available = result as AppUpdateCheck.Available
            assertEquals("1.2.30", available.release.version)
            assertTrue(available.release.apkUrl.contains("debug-latest/tariffiacode-debug.apk"))
            assertTrue(available.release.apkUrl.contains("download=1"))
        }

    @Test
    fun `debug channel is up to date when not newer`() =
        runTest {
            val result = debugClient(debugRelease("1.2.29")).check("1.2.29")
            assertTrue(result is AppUpdateCheck.UpToDate)
        }

    @Test
    fun `debug channel requires the dedicated debug asset and rejects a production asset`() =
        runTest {
            val result =
                runCatching {
                    debugClient(debugRelease("1.2.30", assetName = "tariffiacode-1.2.30-release.apk")).check("1.2.29")
                }
            assertTrue("debug channel must not accept a production release asset", result.isFailure)
        }

    @Test
    fun `debug channel asset name is constant`() {
        assertEquals("tariffiacode-debug.apk", AppUpdateChannel.Debug.apkAssetName("debug-latest"))
        assertEquals("tariffiacode-debug.apk", AppUpdateChannel.Debug.apkAssetName("anything"))
    }

    @Test
    fun `release channel accepts only production tags and tag based asset`() {
        assertTrue(AppUpdateChannel.Release.acceptsTag("v1.2.30"))
        assertFalse("release channel must not accept the debug tag", AppUpdateChannel.Release.acceptsTag("debug-latest"))
        assertEquals("tariffiacode-v1.2.30-release.apk", AppUpdateChannel.Release.apkAssetName("v1.2.30"))
        assertTrue(AppUpdateChannel.Debug.acceptsTag("debug-latest"))
        assertFalse(AppUpdateChannel.Debug.acceptsTag("v1.2.30"))
    }
}
