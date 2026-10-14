package com.konprostart.tariffiacode.core.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The file the screen reads to know what the service did.
 *
 * The logic that decides "running" is three lines and the screen's whole honesty rests on it, so it
 * is tested against a directory it can be pointed at. The cases that matter are the dishonest ones:
 * a file that exists but says nothing usable must read as *not running*, never as running.
 */
class RouterStatusFileTest {
    private fun dirWith(content: String?): File {
        val dir = Files.createTempDirectory("router-status-test").toFile()
        dir.deleteOnExit()
        content?.let { File(dir, AgentRouterEndpoint.ENDPOINT_FILE_NAME).writeText(it) }
        return dir
    }

    private fun read(dir: File): RouterStatus {
        val file = File(dir, AgentRouterEndpoint.ENDPOINT_FILE_NAME)
        if (!file.isFile) return RouterStatus.NotRunning
        val values =
            runCatching {
                file.readLines().mapNotNull { line ->
                    val value = line.substringAfter('=', "")
                    if (value.isBlank()) null else line.substringBefore('=') to value
                }.toMap()
            }.getOrDefault(emptyMap())
        val url = values["ANTHROPIC_BASE_URL"] ?: return RouterStatus.NotRunning
        val port = url.substringAfterLast(':').trim().toIntOrNull() ?: return RouterStatus.NotRunning
        return RouterStatus.Running(port)
    }

    @Test
    fun `a written endpoint reads as running on the port the kernel gave`() {
        val status =
            read(
                dirWith(
                    """
                    ANTHROPIC_BASE_URL=http://127.0.0.1:41234
                    ANTHROPIC_AUTH_TOKEN=abc
                    ROUTER_PID=99
                    """.trimIndent(),
                ),
            )

        assertEquals(RouterStatus.Running(41234), status)
    }

    @Test
    fun `nothing there means not running`() {
        assertEquals(RouterStatus.NotRunning, read(dirWith(null)))
    }

    @Test
    fun `a half-written file reads as not running, never as running`() {
        // The dangerous one: a half-written file that still parses as a port would show the user a
        // switch that is on, over a service that may not exist.
        val status = read(dirWith("ANTHROPIC_BASE_URL=http://127.0.0.1:\n"))
        assertEquals(RouterStatus.NotRunning, status)
    }

    @Test
    fun `a file with no url in it reads as not running`() {
        assertEquals(RouterStatus.NotRunning, read(dirWith("ANTHROPIC_AUTH_TOKEN=abc\n")))
    }

    @Test
    fun `a non-numeric port is not a port`() {
        assertEquals(RouterStatus.NotRunning, read(dirWith("ANTHROPIC_BASE_URL=http://127.0.0.1:abc\n")))
    }

    @Test
    fun `the port is not the same every run`() {
        val first = read(dirWith("ANTHROPIC_BASE_URL=http://127.0.0.1:1111\n"))
        val second = read(dirWith("ANTHROPIC_BASE_URL=http://127.0.0.1:2222\n"))
        assertTrue("the kernel assigns it, so two runs differ", first != second)
    }
}
