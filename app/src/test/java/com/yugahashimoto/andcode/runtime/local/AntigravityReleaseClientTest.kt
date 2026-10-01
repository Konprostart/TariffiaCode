package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AntigravityReleaseClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `resolves the newest release asset for the device ABI`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    releaseJson(
                        tag = "1.1.17",
                        assets =
                            listOf(
                                asset("agy_cli_linux_x64.tar.gz", "a".repeat(64)),
                                asset("agy_cli_linux_arm64.tar.gz", "b".repeat(64), size = 52_175_139),
                                asset("agy_cli_mac_arm64.tar.gz", "c".repeat(64)),
                            ),
                    ),
                ),
            )

            val release = client().latest("arm64-v8a")

            assertEquals("1.1.17", release.version)
            assertEquals("agy_cli_linux_arm64.tar.gz", release.asset.name)
            assertEquals("b".repeat(64), release.asset.sha256)
            assertEquals(52_175_139, release.asset.sizeBytes)
            assertEquals(
                "https://github.com/google-antigravity/antigravity-cli/releases/download/1.1.17/agy_cli_linux_arm64.tar.gz",
                release.asset.url,
            )
            val request = server.takeRequest()
            assertEquals("application/vnd.github+json", request.getHeader("Accept"))
            assertEquals("AndCode", request.getHeader("User-Agent"))
        }

    @Test
    fun `maps x86_64 to the linux x64 asset`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    releaseJson(
                        tag = "v1.2.0",
                        assets = listOf(asset("agy_cli_linux_x64.tar.gz", "d".repeat(64))),
                    ),
                ),
            )

            val release = client().latest("x86_64")

            assertEquals("1.2.0", release.version)
            assertEquals("agy_cli_linux_x64.tar.gz", release.asset.name)
        }

    @Test
    fun `rejects a release without the platform asset`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    releaseJson(
                        tag = "1.1.17",
                        assets = listOf(asset("agy_cli_mac_arm64.tar.gz", "e".repeat(64))),
                    ),
                ),
            )

            val error = runCatching { client().latest("arm64-v8a") }.exceptionOrNull()

            assertTrue(error?.message.orEmpty().contains("does not contain"))
        }

    @Test
    fun `rejects missing GitHub digest`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    releaseJson(
                        tag = "1.1.17",
                        assets =
                            listOf(
                                """{"name":"agy_cli_linux_arm64.tar.gz","size":100,"browser_download_url":"https://github.com/google-antigravity/antigravity-cli/releases/download/1.1.17/agy_cli_linux_arm64.tar.gz","digest":null}""",
                            ),
                    ),
                ),
            )

            val error = runCatching { client().latest("arm64-v8a") }.exceptionOrNull()

            assertTrue(error?.message.orEmpty().contains("digest", ignoreCase = true))
        }

    @Test
    fun `rejects unsupported ABI before network request`() =
        runTest {
            val error = runCatching { client().latest("armeabi-v7a") }.exceptionOrNull()

            assertTrue(error?.message.orEmpty().contains("ABI"))
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `resolution falls back to the pinned release when the lookup fails`() =
        runTest {
            // Nothing is listening anymore: every lookup fails, as it would offline or rate-limited.
            val deadEndpoint = server.url("/dead").also { server.shutdown() }

            val release = resolveAntigravityRelease("arm64-v8a", OkHttpClient(), deadEndpoint)

            assertEquals(AntigravityManifest.VERSION, release.version)
            assertEquals(AntigravityManifest.assetFor("arm64-v8a"), release.asset)
        }

    private fun client() =
        AntigravityReleaseClient(
            httpClient = OkHttpClient(),
            endpoint = server.url("/repos/google-antigravity/antigravity-cli/releases/latest"),
        )

    private fun releaseJson(
        tag: String,
        assets: List<String>,
    ): String =
        """
        {
          "tag_name": "$tag",
          "assets": [${assets.joinToString(",")}]
        }
        """.trimIndent()

    private fun asset(
        name: String,
        digest: String,
        size: Long = 100,
        url: String = "https://github.com/google-antigravity/antigravity-cli/releases/download/1.1.17/$name",
    ): String =
        """
        {
          "name": "$name",
          "size": $size,
          "browser_download_url": "$url",
          "digest": "sha256:$digest"
        }
        """.trimIndent()
}
