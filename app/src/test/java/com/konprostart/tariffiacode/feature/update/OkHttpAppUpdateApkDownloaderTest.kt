package com.konprostart.tariffiacode.feature.update

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OkHttpAppUpdateApkDownloaderTest {
    @Test
    fun `downloads the apk bytes to the destination file`() =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(200).setBody("APK-BYTES"))
            server.start()
            val destination = File.createTempFile("update", ".apk")
            try {
                OkHttpAppUpdateApkDownloader().download(server.url("/update.apk").toString(), destination)
                assertEquals("APK-BYTES", destination.readText())
            } finally {
                server.shutdown()
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
                        OkHttpAppUpdateApkDownloader().download(server.url("/update.apk").toString(), destination)
                    }.isFailure
                assertTrue("expected the download to fail", failed)
                assertTrue("no file should be written", !destination.exists())
            } finally {
                server.shutdown()
            }
        }
}
