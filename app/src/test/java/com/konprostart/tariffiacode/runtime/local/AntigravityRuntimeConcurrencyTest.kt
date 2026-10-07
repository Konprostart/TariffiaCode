package com.konprostart.tariffiacode.runtime.local

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The Antigravity session/process maps are read from the UI thread (listSessions/listMessages/
 * workspacePaths) while a turn writes them from `Dispatchers.IO` and archive/create/set/remove run on
 * their callers' dispatchers. These tests pin that the maps are synchronized (no
 * ConcurrentModificationException) and that the existing lifecycle behaviour is unchanged.
 */
class AntigravityRuntimeConcurrencyTest {
    @get:Rule val folder = TemporaryFolder()

    private fun runtime() = AntigravityRuntime(folder.root, { null })

    @Test
    fun `concurrent session reads and writes do not throw`() {
        val runtime = runtime()
        val threads = 6
        val iterations = 40
        val executor = Executors.newFixedThreadPool(threads)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val start = CountDownLatch(1)
        val tasks =
            (0 until threads).map { t ->
                executor.submit {
                    start.await()
                    repeat(iterations) { i ->
                        val id = "s-${(t + i) % 12}"
                        try {
                            runtime.create(id, "/workspace")
                            runtime.setSessionTitle(id, "title-$i")
                            runtime.setSessionModel(id, "m", null)
                            runtime.archive(id)
                            runtime.listSessions(null)
                            runtime.listMessages(id)
                            runtime.findSession(id)
                            runtime.workspacePaths()
                        } catch (error: Throwable) {
                            errors += error
                        }
                    }
                }
            }
        start.countDown()
        tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        executor.shutdown()

        assertTrue("concurrent access must not throw: $errors", errors.isEmpty())
    }

    @Test
    fun `session lifecycle is preserved`() =
        runTest {
            val runtime = runtime()
            runtime.create("s1", "/workspace", title = "One")
            runtime.create("s2", "/workspace", title = "Two")

            assertEquals(listOf("s1", "s2"), runtime.listSessions("/workspace").map { it.appSessionId })

            runtime.archive("s1")
            assertFalse(runtime.listSessions("/workspace").any { it.appSessionId == "s1" })
            assertEquals("One", runtime.findSession("s1")?.title)

            assertTrue(runtime.remove("s2"))
            assertNull(runtime.findSession("s2"))
            assertTrue(runtime.listMessages("s2").isEmpty())
        }

    @Test
    fun `remove clears the session attachment directory`() =
        runTest {
            val runtime = runtime()
            runtime.create("s3", "/workspace")
            val attachmentDir = File(folder.root, "workspace/.tariffiacode-attachments/s3").apply { mkdirs() }
            File(attachmentDir, "f").writeText("x")

            runtime.remove("s3")

            assertFalse(attachmentDir.exists())
        }

    @Test
    fun `a caller cancelled before starting never runs the suspend operation`() =
        runTest {
            val runtime = runtime()
            runtime.create("s1", "/workspace")

            val job = launch(start = CoroutineStart.LAZY) { runtime.remove("s1") }
            job.cancel()
            job.join()

            assertTrue(job.isCancelled)
            assertTrue("cancelled before start must not remove", runtime.findSession("s1") != null)
        }
}
