package com.konprostart.tariffiacode.core.router

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * The whole path, end to end, against a provider that is actually there.
 *
 * Everything before this was tested against a mock - which can only ever prove the router talks to
 * itself, because the mock answers in the shape the router already expects. Nothing here has ever
 * asked a real vendor a question: whether the OpenAI-compatible translation is right for DeepSeek,
 * whether a tool call survives it, whether the streaming frames come back in a shape a client reads.
 *
 * So this does what a real turn does, from the outside in: writes a routing table to disk, stores a
 * key, starts the service, and asks for a completion. If the translation is wrong, this is where it
 * shows up - as an empty answer or a gateway error rather than text.
 *
 * The key arrives as an instrumentation argument, supplied by a repository secret. It is never
 * committed, never printed, and removed again in [tearDown] so it does not outlive the emulator.
 */
@RunWith(AndroidJUnit4::class)
class RouterRealProviderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()

    private val key: String = args.getString(KEY_ARGUMENT).orEmpty()
    private val provider: String = args.getString(PROVIDER_ARGUMENT)?.takeIf { it.isNotBlank() } ?: "deepseek"
    private val model: String = args.getString(MODEL_ARGUMENT)?.takeIf { it.isNotBlank() } ?: "deepseek-chat"

    private lateinit var store: KeyStore

    @Before
    fun writeConfiguration() {
        // Skipped rather than failed when no key is configured, so a fork or a routine CI run is not
        // reddened by a secret it was never given.
        assumeTrue("no real provider key configured; skipping the live round trip", key.isNotBlank())
        store = RouterKeys(context)
        store.setKey(provider, key)
        writeTable()
    }

    @After
    fun cleanUp() {
        if (this::store.isInitialized) store.removeKey(provider)
        context.getExternalFilesDir(null)?.resolve("router/claude_tiers.json")?.delete()
        context.stopService(android.content.Intent(context, RouterService::class.java))
    }

    @Test
    fun aRealProviderAnswersThroughTheRouter() {
        val port = startRouterAndWaitForItsPort()

        val response =
            request(
                port = port,
                path = "/v1/messages?beta=true",
                body =
                    """
                    {"model":"claude-sonnet-4-5","max_tokens":64,"stream":false,
                    "system":[{"type":"text","text":"you are a coding agent"}],
                    "messages":[{"role":"user","content":[{"type":"text","text":"Reply with the single word: pong"}]}]}
                    """.trimIndent().replace("\n", ""),
            )

        assertEquals(
            "the router did not deliver a turn. Response: $response",
            200,
            response.status,
        )
        assertTrue(
            "the answer was empty, which is what a mistranslated request looks like: $response",
            response.body.contains("content"),
        )
        assertTrue(
            "the answer should carry text, not just a shape: $response",
            response.body.contains("pong"),
        )
    }

    // --- helpers ----------------------------------------------------------------------------

    private fun writeTable() {
        val file =
            RouterConfigSource.userTableFile(context.getExternalFilesDir(null), RouterConfigLoader.DEFAULT_ASSET_NAME)
        requireNotNull(file) { "no external files directory on this device" }
        file.parentFile?.mkdirs()
        // One provider in every tier, so the chain is the single vendor under test rather than a
        // rotation whose outcome depends on which fallbacks happen to be reachable from an emulator.
        val chains =
            RouterTier.entries.joinToString(",") { tier ->
                """{"tier":"${tier.name.lowercase()}","models":[{"provider":"$provider","model":"$model","contextWindow":200000,"supportsTools":true}]}"""
            }
        file.writeText("""{"chains":[$chains]}""")
    }

    private fun startRouterAndWaitForItsPort(): Int {
        context.startService(android.content.Intent(context, RouterService::class.java))
        val file = RouterService.endpointFile(context)
        val deadline = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < deadline) {
            if (file.isFile) {
                val url = file.readLines().firstOrNull { it.startsWith("ANTHROPIC_BASE_URL=") }
                if (url != null) return url.substringAfterLast(':').trim().toInt()
            }
            Thread.sleep(250)
        }
        throw AssertionError("the router never wrote its endpoint file")
    }

    private class Reply(
        val status: Int,
        val body: String,
    )

    private fun request(
        port: Int,
        path: String,
        body: String,
    ): Reply {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15_000
        // Generous, because a real provider can be slow to first token and a timeout here would be
        // indistinguishable from a translation failure.
        connection.readTimeout = 90_000
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer router-test-token")
        connection.doOutput = true
        connection.outputStream.use { it.write(body.toByteArray()) }
        return runCatching {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            Reply(status, stream?.bufferedReader()?.readText().orEmpty())
        }.getOrElse { Reply(-1, "threw ${it::class.simpleName}: ${it.message}") }
            .also { connection.disconnect() }
    }

    private companion object {
        const val KEY_ARGUMENT = "routerRealProviderKey"
        const val PROVIDER_ARGUMENT = "routerRealProvider"
        const val MODEL_ARGUMENT = "routerRealProviderModel"
    }
}
