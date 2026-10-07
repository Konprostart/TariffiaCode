package com.konprostart.tariffiacode.feature.update

import kotlinx.coroutines.runBlocking
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

/**
 * The update flow must be HTTPS end to end even though the app's global network security config
 * permits cleartext for LAN/loopback OpenCode runtimes. These tests exercise the real transport with
 * a self-signed TLS MockWebServer.
 */
class AppUpdateHttpsOnlyTest {
    /** SHA-256 of the literal body "APK-BYTES". */
    private val apkBytesSha = "d38d10a417bad689f64587c1eb852a194765c8843e9367910c895eae7fdc8dc7"

    private val heldCertificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val serverCertificates = HandshakeCertificates.Builder().heldCertificate(heldCertificate).build()
    private val clientCertificates =
        HandshakeCertificates.Builder().addTrustedCertificate(heldCertificate.certificate).build()

    private fun startTlsServer(): MockWebServer {
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        return server
    }

    private fun httpsOnlyClient(): OkHttpClient =
        AppUpdateHttp.httpsOnlyClient()
            .newBuilder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()

    @Test
    fun `an HTTPS download is allowed`() =
        runBlocking {
            val server = startTlsServer()
            server.enqueue(MockResponse().setResponseCode(200).setBody("APK-BYTES"))
            val destination = File.createTempFile("update", ".apk")
            try {
                OkHttpAppUpdateApkDownloader(httpsOnlyClient())
                    .download(server.url("/update.apk").toString(), destination, apkBytesSha)

                assertEquals("APK-BYTES", destination.readText())
            } finally {
                server.shutdown()
                destination.delete()
            }
        }

    @Test
    fun `an HTTPS to HTTP redirect is rejected`() =
        runBlocking {
            val server = startTlsServer()
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "http://127.0.0.1:${server.port}/evil.apk"),
            )
            val destination = File.createTempFile("update", ".apk")
            try {
                val failed =
                    runCatching {
                        OkHttpAppUpdateApkDownloader(httpsOnlyClient())
                            .download(server.url("/update.apk").toString(), destination, apkBytesSha)
                    }.isFailure

                assertTrue("a redirect downgraded to cleartext must be rejected", failed)
                assertFalse("the unverified APK must be deleted", destination.exists())
            } finally {
                server.shutdown()
                destination.delete()
            }
        }

    @Test
    fun `a direct HTTP download URL is rejected before any bytes are kept`() =
        runBlocking {
            val destination = File.createTempFile("update", ".apk").apply { delete() }
            try {
                val failed =
                    runCatching {
                        OkHttpAppUpdateApkDownloader(AppUpdateHttp.httpsOnlyClient())
                            .download("http://insecure.example/update.apk", destination, apkBytesSha)
                    }.isFailure

                assertTrue("a cleartext update URL must be rejected", failed)
                assertFalse(destination.exists())
            } finally {
                destination.delete()
            }
        }

    @Test
    fun `the update check refuses an HTTP asset URL`() =
        runBlocking {
            val json =
                """[{"tag_name":"v9.9.9","draft":false,"prerelease":false,"assets":[""" +
                    """{"name":"tariffiacode-v9.9.9-release.apk","browser_download_url":""" +
                    """"http://insecure.example/tariffiacode-v9.9.9-release.apk","digest":"sha256:${"a".repeat(64)}"}]}]"""

            val result = runCatching { AppUpdateReleaseClient(fetchRelease = { json }).check("1.2.29") }

            assertTrue("an HTTP download URL must not be offered", result.isFailure)
        }
}
