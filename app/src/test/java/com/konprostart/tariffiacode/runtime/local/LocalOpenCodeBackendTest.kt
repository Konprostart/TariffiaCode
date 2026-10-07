package com.konprostart.tariffiacode.runtime.local

import com.konprostart.tariffiacode.data.connection.ConnectionProfile
import com.konprostart.tariffiacode.runtime.remote.RemoteOpenCodeBackend
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalOpenCodeBackendTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `reuses backend for same port and rebuilds after invalidate or port change`() {
        var port = 4097
        val created = mutableListOf<RemoteOpenCodeBackend>()
        val backend =
            LocalOpenCodeBackend(
                portProvider = { port },
                backendFactory = { profile ->
                    RemoteOpenCodeBackend(profile).also { created += it }
                },
            )

        val first = backend.delegate()
        val second = backend.delegate()
        assertSame(first, second)
        assertEquals(1, created.size)

        backend.invalidate()
        val third = backend.delegate()
        assertNotSame(first, third)
        assertEquals(2, created.size)

        port = 4098
        val fourth = backend.delegate()
        assertNotSame(third, fourth)
        assertEquals(3, created.size)
    }

    @Test
    fun `local profile carries the runtime secret and rebuilds when it rotates`() {
        var password: String? = "first-secret"
        val profiles = mutableListOf<ConnectionProfile>()
        val backend =
            LocalOpenCodeBackend(
                portProvider = { 4098 },
                backendFactory = { profile ->
                    profiles += profile
                    RemoteOpenCodeBackend(profile)
                },
                passwordProvider = { password },
            )

        val first = backend.delegate()
        assertEquals("first-secret", profiles.last().password)
        assertEquals(LocalRuntimeServerSecret.USERNAME, profiles.last().username)
        // The loopback URL and port are unchanged: the existing workflow still talks to the same server.
        assertEquals("http://127.0.0.1:4098/", profiles.last().baseUrl)
        assertSame(first, backend.delegate())
        assertEquals(1, profiles.size)

        // A restarted server has a new secret on the same port; the client must not reuse the old one.
        password = "second-secret"
        val second = backend.delegate()
        assertNotSame(first, second)
        assertEquals("second-secret", profiles.last().password)
        assertEquals(2, profiles.size)
    }

    @Test
    fun `delegates answerQuestion through remote backend`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("true"))

            val backend =
                LocalOpenCodeBackend(
                    portProvider = { server.port },
                    backendFactory = { profile ->
                        RemoteOpenCodeBackend(
                            profile.copy(baseUrl = server.url("/").toString()),
                        )
                    },
                )

            assertTrue(backend.answerQuestion("q-1", listOf(listOf("src"), listOf("docs", "tests")), "/workspace/repo"))

            val request = server.takeRequest()
            assertEquals("/question/q-1/reply?directory=%2Fworkspace%2Frepo", request.path)
            assertEquals("""{"answers":[["src"],["docs","tests"]]}""", request.body.readUtf8())
        }
}
