package com.konprostart.tariffiacode.feature.update

import org.junit.Assert.assertEquals
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
    fun `current version is up to date`() {
        val result = client(release("v1.2.27")).check("1.2.27")
        assertTrue(result is AppUpdateCheck.UpToDate)
        assertEquals("1.2.27", result.currentVersion)
    }

    @Test
    fun `newer version is available with the official download url`() {
        val result = client(release("v1.2.28")).check("1.2.27")
        assertTrue(result is AppUpdateCheck.Available)
        val available = result as AppUpdateCheck.Available
        assertEquals("1.2.28", available.release.version)
        assertTrue(available.release.apkUrl.startsWith("https://github.com/Konprostart/TariffiaCode/releases/download/v1.2.28/"))
        assertTrue(available.release.apkUrl.contains("download=1"))
    }

    @Test
    fun `older latest release is up to date`() {
        val result = client(release("v1.2.26")).check("1.2.27")
        assertTrue(result is AppUpdateCheck.UpToDate)
    }

    @Test
    fun `malformed tag is ignored rather than crashing`() {
        val payload = "[${release("not-a-version")}]"
        val result = AppUpdateReleaseClient(fetchRelease = { payload }).check("1.2.27")
        assertTrue(result is AppUpdateCheck.UpToDate)
    }

    @Test
    fun `draft and prerelease are skipped`() {
        val payload = listOf(release("v1.2.30", prerelease = true), release("v1.2.29", draft = true)).joinToString(",", "[", "]")
        val result = AppUpdateReleaseClient(fetchRelease = { payload }).check("1.2.27")
        assertTrue(result is AppUpdateCheck.UpToDate)
    }

    @Test
    fun `newer release missing the apk asset fails`() {
        val result = runCatching { client(release("v1.2.28", withApk = false)).check("1.2.27") }
        assertTrue(result.isFailure)
    }

    @Test
    fun `network or api failure surfaces as a failure`() {
        val client = AppUpdateReleaseClient(fetchRelease = { error("TariffiaCode release check failed with HTTP 500") })
        val result = runCatching { client.check("1.2.27") }
        assertTrue(result.isFailure)
    }
}
