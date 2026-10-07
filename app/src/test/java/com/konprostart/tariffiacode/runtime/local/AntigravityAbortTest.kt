package com.konprostart.tariffiacode.runtime.local

import com.konprostart.tariffiacode.runtime.RuntimeState
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Antigravity's abort/disconnect used to sleep the caller's thread (`Thread.sleep(2000)`), which the
 * chat reaches from the UI thread. The wait is now a cancellable suspension, and the runtime's abort
 * entry points are `suspend`, so the whole sequence stays off the UI thread.
 */
class AntigravityAbortTest {
    @get:Rule val folder = TemporaryFolder()

    private fun target(): AntigravityTarget = AntigravityTarget(AntigravityRuntime(folder.root, { null }))

    @Test
    fun `requests a graceful stop, then force-kills a still-running process`() =
        runTest {
            val calls = mutableListOf<String>()

            AntigravityAbort.gracefully(
                graceMillis = 100,
                requestStop = { calls += "stop" },
                isAlive = { true },
                terminate = { calls += "kill" },
            )

            assertEquals(listOf("stop", "kill"), calls)
        }

    @Test
    fun `does not force-kill a process that exited during the grace period`() =
        runTest {
            val calls = mutableListOf<String>()
            var alive = true

            AntigravityAbort.gracefully(
                graceMillis = 100,
                requestStop = {
                    calls += "stop"
                    alive = false
                },
                isAlive = { alive },
                terminate = { calls += "kill" },
            )

            assertEquals(listOf("stop"), calls)
        }

    @Test
    fun `a failing stop request does not skip the kill fallback`() =
        runTest {
            val calls = mutableListOf<String>()

            AntigravityAbort.gracefully(
                graceMillis = 100,
                requestStop = { throw IOException("broken pipe") },
                isAlive = { true },
                terminate = { calls += "kill" },
            )

            assertEquals(listOf("kill"), calls)
        }

    @Test
    fun `the grace wait is cancellable, so it never blocks the caller`() =
        runTest {
            var killed = false
            val job =
                launch {
                    AntigravityAbort.gracefully(
                        graceMillis = 10_000,
                        requestStop = {},
                        isAlive = { true },
                        terminate = { killed = true },
                    )
                }

            runCurrent()
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertFalse("cancellation must stop the sequence before the kill fallback", killed)
        }

    @Test
    fun `aborting a session with no running process still reports success`() =
        runBlocking {
            assertTrue(target().abortSession("no-such-session"))
        }

    @Test
    fun `disconnect stops the target`() {
        val target = target()

        target.disconnect()

        assertTrue(target.state.value is RuntimeState.Disconnected)
    }

    @Test
    fun `no blocking Thread sleep remains in the abort or disconnect path`() {
        listOf("AntigravityRuntime.kt", "AntigravityTarget.kt", "AntigravityAbort.kt").forEach { name ->
            val file = sourceFile("src/main/java/com/konprostart/tariffiacode/runtime/local/$name")
            assertFalse("$name must not block with Thread.sleep", file.readText().contains("Thread.sleep"))
        }
    }

    private fun sourceFile(relative: String): File =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?: error("could not locate $relative from ${File(".").absolutePath}")
}
