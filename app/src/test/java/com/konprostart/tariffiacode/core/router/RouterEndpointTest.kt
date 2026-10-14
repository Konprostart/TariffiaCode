package com.konprostart.tariffiacode.core.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * How an agent finds the router, and what happens when there is nothing to find.
 *
 * The second case is the one that matters most. A router that is switched off must be invisible: an
 * agent that cannot start because routing is off would be a worse product than one that never knew
 * there was a router. So the absence of an endpoint has to be the ordinary answer, not an error.
 */
class RouterEndpointTest {
    private fun runtimeWith(content: String?): File {
        val dir = Files.createTempDirectory("router-endpoint-test").toFile()
        dir.deleteOnExit()
        content?.let { File(dir, RouterEndpoint.ENDPOINT_FILE_NAME).writeText(it) }
        return dir
    }

    @Test
    fun `a running router becomes a base url and a token`() {
        val dir =
            runtimeWith(
                """
                ANTHROPIC_BASE_URL=http://127.0.0.1:41234
                ANTHROPIC_AUTH_TOKEN=a-token
                ANTHROPIC_API_KEY=a-token
                ROUTER_PID=1234
                """.trimIndent(),
            )

        assertEquals(
            mapOf(
                "ANTHROPIC_BASE_URL" to "http://127.0.0.1:41234",
                "ANTHROPIC_AUTH_TOKEN" to "a-token",
            ),
            RouterEndpoint.environmentFor(dir),
        )
    }

    @Test
    fun `no router means no environment, and no failure`() {
        // The state that must never be able to stop an agent starting.
        assertTrue(RouterEndpoint.environmentFor(runtimeWith(null)).isEmpty())
    }

    @Test
    fun `a half-written file is ignored rather than half-applied`() {
        val dir = runtimeWith("ANTHROPIC_BASE_URL=http://127.0.0.1:41234\n")

        assertTrue(
            "a base url with no token is not a route anywhere: ${RouterEndpoint.environmentFor(dir)}",
            RouterEndpoint.environmentFor(dir).isEmpty(),
        )
    }

    @Test
    fun `a blank token is not a token`() {
        val dir =
            runtimeWith(
                """
                ANTHROPIC_BASE_URL=http://127.0.0.1:41234
                ANTHROPIC_AUTH_TOKEN=
                """.trimIndent(),
            )

        assertTrue(RouterEndpoint.environmentFor(dir).isEmpty())
    }
}
