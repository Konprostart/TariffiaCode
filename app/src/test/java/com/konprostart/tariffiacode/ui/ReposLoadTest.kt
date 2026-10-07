package com.konprostart.tariffiacode.ui

import com.konprostart.tariffiacode.feature.settings.GitHubRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The clone dialog's repository listing runs in a `LaunchedEffect`; an exception escaping it crashes
 * the app. [loadReposForDialog] is the seam that keeps expected failures in an error state, while
 * still letting cancellation propagate.
 */
class ReposLoadTest {
    private fun repo(fullName: String) =
        GitHubRepo(name = fullName.substringAfterLast('/'), fullName = fullName, cloneUrl = "https://github.com/$fullName.git")

    @Test
    fun `success returns the loaded repositories`() =
        runTest {
            val result = loadReposForDialog { listOf(repo("a/b"), repo("c/d")) }

            assertTrue(result is ReposLoadResult.Loaded)
            assertEquals(listOf("a/b", "c/d"), (result as ReposLoadResult.Loaded).repos.map { it.fullName })
        }

    @Test
    fun `an expected network failure becomes a Failed result instead of crashing`() =
        runTest {
            val result = loadReposForDialog { throw IOException("no network") }

            assertEquals(ReposLoadResult.Failed, result)
        }

    @Test
    fun `an unexpected runtime exception also becomes a Failed result`() =
        runTest {
            val result = loadReposForDialog { throw IllegalStateException("unexpected payload shape") }

            assertEquals(ReposLoadResult.Failed, result)
        }

    @Test
    fun `cancellation is rethrown, not swallowed`() {
        val thrown =
            assertThrows(CancellationException::class.java) {
                runBlocking { loadReposForDialog { throw CancellationException("cancelled") } }
            }

        assertEquals("cancelled", thrown.message)
    }
}
