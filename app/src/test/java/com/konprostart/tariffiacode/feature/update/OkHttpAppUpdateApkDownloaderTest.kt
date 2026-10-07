package com.konprostart.tariffiacode.feature.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Exercises the downloader's content verification over plain HTTP MockWebServer, so it injects a
 * transport-permissive client. The HTTPS-only transport policy is covered separately by
 * `AppUpdateHttpsOnlyTest`.
 */
class OkHttpAppUpdateApkDownloaderTest {
    /** SHA-256 of the literal body "APK-BYTES" used by the success cases. */
    private val apkBytesSha = "d38d10a417bad689f64587c1eb852a194765c8843e9367910c895eae7fdc8dc7"

    @Test
    fun `downloads and keeps an APK whose SHA-256 matches`() =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(200).setBody("APK-BYTES"))
            server.start()
            val destination = File.createTempFile("update", ".apk")
            try {
                OkHttpAppUpdateApkDownloader(OkHttpClient()).download(
                    server.url("/update.apk").toString(),
                    destination,
                    apkBytesSha,
                    expectedSizeBytes = "APK-BYTES".length.toLong(),
                )
                assertEquals("APK-BYTES", destination.readText())
            } finally {
                server.shutdown()
                destination.delete()
            }
        }

    @Test
    fun `the expected hash is matched case-insensitively`() =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(200).setBody("APK-BYTES"))
            server.start()
            val destination = File.createTempFile("update", ".apk")
            try {
                OkHttpAppUpdateApkDownloader(OkHttpClient()).download(
                    server.url("/update.apk").toString(),
                    destination,
                    apkBytesSha.uppercase(),
                )
                assertEquals("APK-BYTES", destination.readText())
            } finally {
                server.shutdown()
                destination.delete()
            }
        }

    @Test
    fun `a hash mismatch is rejected and the file is deleted`() =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(200).setBody("APK-BYTES"))
            server.start()
            val destination = File.createTempFile("update", ".apk")
            try {
                val wrongHash = "0".repeat(64)
                val failed =
                    runCatching {
                        OkHttpAppUpdateApkDownloader(OkHttpClient()).download(server.url("/update.apk").toString(), destination, wrongHash)
                    }.isFailure
                assertTrue("expected a hash mismatch to fail", failed)
                assertFalse("the unverified APK must be deleted", destination.exists())
            } finally {
                server.shutdown()
                destination.delete()
            }
        }

    @Test
    fun `a corrupt or partial body is rejected and the file is deleted`() =
        runBlocking {
            val server = MockWebServer()
            // Truncated body: the expected hash is for the full "APK-BYTES".
            server.enqueue(MockResponse().setResponseCode(200).setBody("APK-BYT"))
            server.start()
            val destination = File.createTempFile("update", ".apk")
            try {
                val failed =
                    runCatching {
                        OkHttpAppUpdateApkDownloader(OkHttpClient())
                            .download(server.url("/update.apk").toString(), destination, apkBytesSha)
                    }.isFailure
                assertTrue("expected a partial body to fail verification", failed)
                assertFalse("the corrupt APK must be deleted", destination.exists())
            } finally {
                server.shutdown()
                destination.delete()
            }
        }

    @Test
    fun `a size mismatch is rejected and the file is deleted`() =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(200).setBody("APK-BYTES"))
            server.start()
            val destination = File.createTempFile("update", ".apk")
            try {
                val failed =
                    runCatching {
                        OkHttpAppUpdateApkDownloader(OkHttpClient()).download(
                            server.url("/update.apk").toString(),
                            destination,
                            apkBytesSha,
                            expectedSizeBytes = 999L,
                        )
                    }.isFailure
                assertTrue("expected a size mismatch to fail", failed)
                assertFalse("the truncated APK must be deleted", destination.exists())
            } finally {
                server.shutdown()
                destination.delete()
            }
        }

    @Test
    fun `a non-success response throws and writes nothing`() =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(404))
            server.start()
            val destination = File.createTempFile("update", ".apk").apply { delete() }
            try {
                val failed =
                    runCatching {
                        OkHttpAppUpdateApkDownloader(OkHttpClient())
                            .download(server.url("/update.apk").toString(), destination, apkBytesSha)
                    }.isFailure
                assertTrue("expected the download to fail", failed)
                assertTrue("no file should be written", !destination.exists())
            } finally {
                server.shutdown()
            }
        }
}
