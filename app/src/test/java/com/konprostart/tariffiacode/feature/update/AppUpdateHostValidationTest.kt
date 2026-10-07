package com.konprostart.tariffiacode.feature.update

import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress

/**
 * The update flow must only talk to GitHub: the metadata API host, the asset host, and the one CDN
 * host GitHub redirects the asset URL to. Hosts here are the ones observed in the real flow.
 */
class AppUpdateHostValidationTest {
    /** SHA-256 of the literal body "APK-BYTES". */
    private val apkBytesSha = "d38d10a417bad689f64587c1eb852a194765c8843e9367910c895eae7fdc8dc7"
    private val sha = "a".repeat(64)

    private val heldCertificate =
        HeldCertificate.Builder()
            .addSubjectAlternativeName(AppUpdateHttp.ASSET_HOST)
            .addSubjectAlternativeName(AppUpdateHttp.ASSET_CDN_HOST)
            .build()
    private val serverCertificates = HandshakeCertificates.Builder().heldCertificate(heldCertificate).build()
    private val clientCertificates =
        HandshakeCertificates.Builder().addTrustedCertificate(heldCertificate.certificate).build()

    private fun startTlsServer(): MockWebServer {
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        return server
    }

    /** The asset client, resolving every hostname to the local test server. */
    private fun assetClient(): OkHttpClient =
        AppUpdateHttp.assetClient()
            .newBuilder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .dns(
                object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
                },
            )
            .build()

    // 1. valid API host -> allowed
    @Test
    fun `the expected GitHub API host is allowed`() {
        val url = requireGitHubApiEndpoint("https://api.github.com/repos/Konprostart/TariffiaCode/releases")
        assertEquals(AppUpdateHttp.API_HOST, url.host)
    }

    // 2. wrong API host -> rejected
    @Test
    fun `an unexpected or cleartext API host is rejected`() {
        assertTrue(runCatching { requireGitHubApiEndpoint("https://evil.example/releases") }.isFailure)
        assertTrue(runCatching { requireGitHubApiEndpoint("http://api.github.com/releases") }.isFailure)
    }

    // 3. valid APK host -> allowed
    @Test
    fun `a GitHub asset URL is accepted`() =
        runBlocking {
            val json = releaseJson("https://github.com/Konprostart/TariffiaCode/releases/download/v9.9.9/x.apk")
            val result = AppUpdateReleaseClient(fetchRelease = { json }).check("1.2.29")
            assertTrue(result is AppUpdateCheck.Available)
        }

    // 4. other host -> rejected
    @Test
    fun `an asset URL on another host is rejected`() =
        runBlocking {
            val json = releaseJson("https://evil.example/x.apk")
            assertTrue(runCatching { AppUpdateReleaseClient(fetchRelease = { json }).check("1.2.29") }.isFailure)
        }

    // 5. redirect to another host -> rejected
    @Test
    fun `a redirect to another host is rejected`() =
        runBlocking {
            val server = startTlsServer()
            server.enqueue(
                MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example:${server.port}/x.apk"),
            )
            val destination = File.createTempFile("update", ".apk")
            try {
                val failed =
                    runCatching {
                        OkHttpAppUpdateApkDownloader(assetClient())
                            .download("https://${AppUpdateHttp.ASSET_HOST}:${server.port}/update.apk", destination, apkBytesSha)
                    }.isFailure

                assertTrue("a redirect to an unexpected host must be rejected", failed)
                assertFalse(destination.exists())
            } finally {
                server.shutdown()
                destination.delete()
            }
        }

    // 6. valid update flow: github.com -> release-assets.githubusercontent.com -> allowed
    @Test
    fun `the real GitHub asset redirect flow is allowed`() =
        runBlocking {
            val server = startTlsServer()
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "https://${AppUpdateHttp.ASSET_CDN_HOST}:${server.port}/asset.apk"),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("APK-BYTES"))
            val destination = File.createTempFile("update", ".apk")
            try {
                OkHttpAppUpdateApkDownloader(assetClient())
                    .download("https://${AppUpdateHttp.ASSET_HOST}:${server.port}/update.apk", destination, apkBytesSha)

                assertEquals("APK-BYTES", destination.readText())
            } finally {
                server.shutdown()
                destination.delete()
            }
        }

    private fun releaseJson(url: String): String =
        """[{"tag_name":"v9.9.9","draft":false,"prerelease":false,"assets":[""" +
            """{"name":"tariffiacode-v9.9.9-release.apk","browser_download_url":"$url","digest":"sha256:$sha"}]}]"""
}
