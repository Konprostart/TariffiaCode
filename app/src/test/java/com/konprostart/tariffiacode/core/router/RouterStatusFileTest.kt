package com.konprostart.tariffiacode.core.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

/**
 * What the screen reads to decide "running", and what it reads when it should say "not running".
 *
 * The decision has two inputs and only one of them is a file. The file says which port to check; a
 * probe says whether a server is actually on it. That distinction is the whole point: the file
 * outlives the process that wrote it, so a file alone turns a router Android killed into a switch
 * that stays on forever.
 *
 * These tests drive the production helpers ([AgentRouterEndpoint.portOf] and
 * [AgentRouterEndpoint.decideStatus]) rather than a copy of them. An earlier version of this file
 * re-implemented the parsing locally, which meant it went on asserting "a file with a port in it is
 * running" long after the product had stopped believing that - six green tests describing a contract
 * the code no longer had.
 */
class RouterStatusFileTest {
    private fun dirWith(content: String?): File {
        val dir = Files.createTempDirectory("router-status-test").toFile()
        dir.deleteOnExit()
        content?.let { File(dir, AgentRouterEndpoint.ENDPOINT_FILE_NAME).writeText(it) }
        return dir
    }

    /** The production path: parse the file, then prove the port the same way the service does. */
    private fun read(dir: File): RouterStatus {
        val port = AgentRouterEndpoint.portOf(File(dir, AgentRouterEndpoint.ENDPOINT_FILE_NAME))
        val accepting = port != null && AgentRouterEndpoint.isAccepting(port)
        return AgentRouterEndpoint.decideStatus(port, accepting)
    }

    private fun endpoint(port: Int): String =
        """
        ANTHROPIC_BASE_URL=http://127.0.0.1:$port
        ANTHROPIC_AUTH_TOKEN=abc
        ROUTER_PID=99
        """.trimIndent()

    @Test
    fun `a file naming a port nothing is listening on reads as not running`() {
        // The bug this whole change exists for: a router whose process was killed leaves its file
        // behind, and a file-only check reported it as serving.
        val port =
            ServerSocket(0).use { socket ->
                socket.localPort
            }

        assertEquals(RouterStatus.NotRunning, read(dirWith(endpoint(port))))
    }

    @Test
    fun `a file naming a live server reads as running on that port`() {
        ServerSocket(0).use { live ->
            val status = read(dirWith(endpoint(live.localPort)))

            assertEquals(RouterStatus.Running(live.localPort), status)
        }
    }

    @Test
    fun `nothing there means not running`() {
        assertEquals(RouterStatus.NotRunning, read(dirWith(null)))
    }

    @Test
    fun `a half-written file reads as not running, never as running`() {
        // The dangerous one: a half-written file that still parses as a port would show the user a
        // switch that is on, over a service that may not exist.
        assertEquals(RouterStatus.NotRunning, read(dirWith("ANTHROPIC_BASE_URL=http://127.0.0.1:\n")))
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
    fun `a port outside the bindable range is not a port`() {
        // Passed the liveness check or not, no socket can be on 70000, so it can never be running.
        assertEquals(null, AgentRouterEndpoint.portOf(File(dirWith(endpoint(70_000)), AgentRouterEndpoint.ENDPOINT_FILE_NAME)))
        assertEquals(null, AgentRouterEndpoint.portOf(File(dirWith(endpoint(0)), AgentRouterEndpoint.ENDPOINT_FILE_NAME)))
    }

    @Test
    fun `the port is not the same every run`() {
        val first = AgentRouterEndpoint.portOf(File(dirWith(endpoint(1111)), AgentRouterEndpoint.ENDPOINT_FILE_NAME))
        val second = AgentRouterEndpoint.portOf(File(dirWith(endpoint(2222)), AgentRouterEndpoint.ENDPOINT_FILE_NAME))

        assertTrue("the kernel assigns it, so two runs differ", first != second)
    }
}
