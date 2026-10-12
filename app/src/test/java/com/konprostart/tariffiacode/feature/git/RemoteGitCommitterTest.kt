package com.konprostart.tariffiacode.feature.git

import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteGitCommitterTest {
    private class FakeExecutor(
        var outcome: RemoteCommandOutcome,
    ) : RemoteGitCommandExecutor {
        var lastScript: String? = null

        override suspend fun execute(
            profile: SshProfile,
            script: String,
            timeoutMillis: Long,
        ): RemoteCommandOutcome {
            lastScript = script
            return outcome
        }
    }

    private fun profile() =
        SshProfile(
            id = "vps-1",
            name = "VPS",
            host = "example.com",
            username = "root",
            authType = SshAuthType.PASSWORD,
            credentialRef = "ref",
        )

    @Test
    fun `a successful commit stages everything then commits and returns the git output`() =
        runTest {
            val executor = FakeExecutor(RemoteCommandOutcome.Completed(0, "[main abc123] Initial import\n"))
            val result = RemoteGitCommitter(executor).commit(profile(), "/root/ollama", "Initial import")

            assertTrue(result is RemoteGitCommitResult.Success)
            assertTrue((result as RemoteGitCommitResult.Success).output.contains("abc123"))
            val script = executor.lastScript.orEmpty()
            assertTrue(script.contains("git -C '/root/ollama' add -A"))
            assertTrue(script.contains("git -C '/root/ollama' commit -m 'Initial import'"))
            assertTrue("no fixed exit marker", !script.contains("__TC_EXIT__"))
        }

    @Test
    fun `a commit message containing a quote is single-quoted safely`() =
        runTest {
            val executor = FakeExecutor(RemoteCommandOutcome.Completed(0, "ok\n"))

            RemoteGitCommitter(executor).commit(profile(), "/root/ollama", "it's done")

            assertTrue(executor.lastScript.orEmpty().contains("'it'\\''s done'"))
        }

    @Test
    fun `a blank message is rejected before any execution`() =
        runTest {
            val executor = FakeExecutor(RemoteCommandOutcome.Completed(0, ""))

            assertTrue(RemoteGitCommitter(executor).commit(profile(), "/root/ollama", "   ") is RemoteGitCommitResult.Invalid)
            assertNull(executor.lastScript)
        }

    @Test
    fun `an invalid path is rejected before any execution`() =
        runTest {
            val executor = FakeExecutor(RemoteCommandOutcome.Completed(0, ""))

            assertTrue(RemoteGitCommitter(executor).commit(profile(), "relative/path", "msg") is RemoteGitCommitResult.Invalid)
            assertNull(executor.lastScript)
        }

    @Test
    fun `a non-zero exit is a commit failure`() =
        runTest {
            val executor = FakeExecutor(RemoteCommandOutcome.Completed(1, "nothing to commit, working tree clean\n"))
            val result = RemoteGitCommitter(executor).commit(profile(), "/root/ollama", "msg")

            assertTrue(result is RemoteGitCommitResult.Failed)
            assertEquals("git commit failed (exit 1)", (result as RemoteGitCommitResult.Failed).message)
        }

    @Test
    fun `missing credential is surfaced`() =
        runTest {
            val result = RemoteGitCommitter(FakeExecutor(RemoteCommandOutcome.MissingCredential)).commit(profile(), "/root/ollama", "msg")
            assertEquals(RemoteGitCommitResult.MissingCredential, result)
        }

    @Test
    fun `untrusted host key is surfaced`() =
        runTest {
            val key = SshHostKey("ssh-ed25519", "ab".repeat(32))
            val result =
                RemoteGitCommitter(FakeExecutor(RemoteCommandOutcome.HostKeyUntrusted(key)))
                    .commit(profile(), "/root/ollama", "msg")
            assertTrue(result is RemoteGitCommitResult.HostKeyUntrusted)
        }
}
