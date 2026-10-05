package com.konprostart.tariffiacode.feature.git

import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteGitPusherTest {
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
    fun `a successful push returns the git output`() =
        runTest {
            val executor = FakeExecutor(RemoteCommandOutcome.Completed("Everything up-to-date\n__TC_EXIT__0\n"))
            val result = RemoteGitPusher(executor).push(profile(), "/root/projects/app")

            assertTrue(result is RemoteGitPushResult.Success)
            assertTrue((result as RemoteGitPushResult.Success).output.contains("Everything up-to-date"))
            val script = executor.lastScript.orEmpty()
            assertTrue(script.contains("git -C '/root/projects/app' push"))
            assertTrue(script.contains(SshShellCommandExecutor.EXIT_MARKER))
        }

    @Test
    fun `a directory that is not a git repository is reported as a failure`() =
        runTest {
            val executor = FakeExecutor(RemoteCommandOutcome.Completed("Not a git repository: '/root/x'\n__TC_EXIT__128\n"))
            val result = RemoteGitPusher(executor).push(profile(), "/root/x")

            assertTrue(result is RemoteGitPushResult.Failed)
            assertEquals("git push failed (exit 128)", (result as RemoteGitPushResult.Failed).message)
        }

    @Test
    fun `a non-zero exit is a push failure`() =
        runTest {
            val executor = FakeExecutor(RemoteCommandOutcome.Completed("error: failed to push\n__TC_EXIT__1\n"))
            val result = RemoteGitPusher(executor).push(profile(), "/root/projects/app")
            assertTrue(result is RemoteGitPushResult.Failed)
        }

    @Test
    fun `an empty or invalid path is rejected before any execution`() =
        runTest {
            val executor = FakeExecutor(RemoteCommandOutcome.Completed("__TC_EXIT__0"))
            val pusher = RemoteGitPusher(executor)

            assertTrue(pusher.push(profile(), " ") is RemoteGitPushResult.Invalid)
            assertTrue(pusher.push(profile(), "relative/path") is RemoteGitPushResult.Invalid)
            assertNull(executor.lastScript)
        }

    @Test
    fun `missing credential is surfaced`() =
        runTest {
            val result = RemoteGitPusher(FakeExecutor(RemoteCommandOutcome.MissingCredential)).push(profile(), "/root/app")
            assertEquals(RemoteGitPushResult.MissingCredential, result)
        }

    @Test
    fun `untrusted host key is surfaced`() =
        runTest {
            val key = SshHostKey("ssh-ed25519", "ab".repeat(32))
            val result = RemoteGitPusher(FakeExecutor(RemoteCommandOutcome.HostKeyUntrusted(key))).push(profile(), "/root/app")
            assertTrue(result is RemoteGitPushResult.HostKeyUntrusted)
        }

    @Test
    fun `a shell failure is surfaced`() =
        runTest {
            val result = RemoteGitPusher(FakeExecutor(RemoteCommandOutcome.Failed("connection refused"))).push(profile(), "/root/app")
            assertEquals("connection refused", (result as RemoteGitPushResult.Failed).message)
        }
}
