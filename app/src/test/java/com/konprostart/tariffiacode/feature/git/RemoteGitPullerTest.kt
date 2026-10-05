package com.konprostart.tariffiacode.feature.git

import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteGitPullerTest {
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
    fun `a successful pull returns the git output`() =
        runTest {
            val executor = FakeExecutor(RemoteCommandOutcome.Completed("Already up to date.\n__TC_EXIT__0\n"))
            val result = RemoteGitPuller(executor).pull(profile(), "/root/projects/app")

            assertTrue(result is RemoteGitPullResult.Success)
            assertTrue((result as RemoteGitPullResult.Success).output.contains("Already up to date"))
            val script = executor.lastScript.orEmpty()
            assertTrue(script.contains("git -C '/root/projects/app' pull --ff-only"))
            assertTrue(script.contains(SshShellCommandExecutor.EXIT_MARKER))
        }

    @Test
    fun `a directory that is not a git repository is reported as a failure`() =
        runTest {
            val executor = FakeExecutor(RemoteCommandOutcome.Completed("Not a git repository: '/root/x'\n__TC_EXIT__128\n"))
            val result = RemoteGitPuller(executor).pull(profile(), "/root/x")

            assertTrue(result is RemoteGitPullResult.Failed)
            assertEquals("git pull failed (exit 128)", (result as RemoteGitPullResult.Failed).message)
        }

    @Test
    fun `a non-zero exit is a pull failure`() =
        runTest {
            val executor = FakeExecutor(RemoteCommandOutcome.Completed("error: cannot pull\n__TC_EXIT__1\n"))
            val result = RemoteGitPuller(executor).pull(profile(), "/root/projects/app")
            assertTrue(result is RemoteGitPullResult.Failed)
        }

    @Test
    fun `an empty or invalid path is rejected before any execution`() =
        runTest {
            val executor = FakeExecutor(RemoteCommandOutcome.Completed("__TC_EXIT__0"))
            val puller = RemoteGitPuller(executor)

            assertTrue(puller.pull(profile(), " ") is RemoteGitPullResult.Invalid)
            assertTrue(puller.pull(profile(), "relative/path") is RemoteGitPullResult.Invalid)
            assertNull(executor.lastScript)
        }

    @Test
    fun `missing credential is surfaced`() =
        runTest {
            val result = RemoteGitPuller(FakeExecutor(RemoteCommandOutcome.MissingCredential)).pull(profile(), "/root/app")
            assertEquals(RemoteGitPullResult.MissingCredential, result)
        }

    @Test
    fun `untrusted host key is surfaced`() =
        runTest {
            val key = SshHostKey("ssh-ed25519", "ab".repeat(32))
            val result = RemoteGitPuller(FakeExecutor(RemoteCommandOutcome.HostKeyUntrusted(key))).pull(profile(), "/root/app")
            assertTrue(result is RemoteGitPullResult.HostKeyUntrusted)
        }

    @Test
    fun `a shell failure is surfaced`() =
        runTest {
            val result = RemoteGitPuller(FakeExecutor(RemoteCommandOutcome.Failed("connection refused"))).pull(profile(), "/root/app")
            assertEquals("connection refused", (result as RemoteGitPullResult.Failed).message)
        }
}
