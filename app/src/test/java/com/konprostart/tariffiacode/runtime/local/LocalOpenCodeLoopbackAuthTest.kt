package com.konprostart.tariffiacode.runtime.local

import com.konprostart.tariffiacode.core.api.OpenCodeApiException
import com.konprostart.tariffiacode.data.connection.ConnectionProfile
import com.konprostart.tariffiacode.runtime.remote.RemoteOpenCodeBackend
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local OpenCode server is protected with HTTP Basic auth (`OPENCODE_SERVER_PASSWORD`); these
 * tests pin the client side of that contract: no auth and a wrong secret are rejected, the runtime's
 * secret is accepted, and the secret never leaks into a log-facing string.
 */
class LocalOpenCodeLoopbackAuthTest {
    private fun authenticatedServer(secret: String): MockWebServer {
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val expected = Credentials.basic(LocalRuntimeServerSecret.USERNAME, secret)
                    return if (request.getHeader("Authorization") == expected) {
                        MockResponse().setBody("""{"healthy":true,"version":"1.0.0"}""")
                    } else {
                        MockResponse().setResponseCode(401)
                    }
                }
            }
        server.start()
        return server
    }

    private fun profile(
        server: MockWebServer,
        password: String?,
    ): ConnectionProfile =
        ConnectionProfile(
            id = "local-android",
            name = "Android local",
            baseUrl = server.url("/").toString(),
            username = LocalRuntimeServerSecret.USERNAME,
            password = password,
            allowInsecureLan = true,
        )

    @Test
    fun `a request without auth is denied`() =
        runBlocking {
            val server = authenticatedServer("s3cret")
            try {
                val backend = RemoteOpenCodeBackend(profile(server, password = null))

                val error = runCatching { backend.health() }.exceptionOrNull()

                assertTrue(error is OpenCodeApiException)
                assertEquals(401, (error as OpenCodeApiException).statusCode)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `a request with the wrong secret is denied`() =
        runBlocking {
            val server = authenticatedServer("s3cret")
            try {
                val backend = RemoteOpenCodeBackend(profile(server, password = "not-the-secret"))

                val error = runCatching { backend.health() }.exceptionOrNull()

                assertTrue(error is OpenCodeApiException)
                assertEquals(401, (error as OpenCodeApiException).statusCode)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `the runtime secret is accepted`() =
        runBlocking {
            val server = authenticatedServer("s3cret")
            try {
                val backend = RemoteOpenCodeBackend(profile(server, password = "s3cret"))

                val health = backend.health()

                assertTrue(health.healthy)
                assertEquals("1.0.0", health.version)
                val request = server.takeRequest()
                assertEquals(Credentials.basic(LocalRuntimeServerSecret.USERNAME, "s3cret"), request.getHeader("Authorization"))
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `the secret never reaches a log-facing string`() =
        runBlocking {
            val server = authenticatedServer("s3cret")
            try {
                val secret = "s3cret"
                val denied = RemoteOpenCodeBackend(profile(server, password = "wrong"))
                val error = runCatching { denied.health() }.exceptionOrNull()

                assertFalse(profile(server, secret).toString().contains(secret))
                assertFalse(error?.message.orEmpty().contains(secret))
            } finally {
                server.shutdown()
            }
        }
}
