package com.konprostart.tariffiacode.feature.git

import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.data.remote.RemoteGitCloneRequest
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteGitClonerTest {
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

    private fun request(
        url: String = "https://github.com/Konprostart/TariffiaCode.git",
        path: String = "/root/projects/TariffiaCode",
    ) = RemoteGitCloneRequest(url, path)

    @Test
    fun `a successful clone returns the git output`() =
        runTest {
            val executor = FakeExecutor(RemoteCommandOutcome.Completed("Cloning into 'TariffiaCode'...\n__TC_EXIT__0\n"))
            val result = RemoteGitCloner(executor).clone(profile(), request())

            assertTrue(result is RemoteGitCloneResult.Success)
            assertTrue((result as RemoteGitCloneResult.Success).output.contains("Cloning into"))
            assertTrue(executor.lastScript.orEmpty().contains("git clone --depth 1"))
        }

    @Test
    fun `an existing non-empty target is refused`() =
        runTest {
            val executor = FakeExecutor(RemoteCommandOutcome.Completed("__TC_NONEMPTY__\n"))
            val result = RemoteGitCloner(executor).clone(profile(), request())
            assertEquals(RemoteGitCloneResult.TargetNotEmpty, result)
        }

    @Test
    fun `a non-zero exit is a clone failure`() =
        runTest {
            val executor = FakeExecutor(RemoteCommandOutcome.Completed("fatal: repository not found\n__TC_EXIT__128\n"))
            val result = RemoteGitCloner(executor).clone(profile(), request())
            assertTrue(result is RemoteGitCloneResult.Failed)
            assertEquals("git clone failed (exit 128)", (result as RemoteGitCloneResult.Failed).message)
        }

    @Test
    fun `missing credential is surfaced`() =
        runTest {
            val result = RemoteGitCloner(FakeExecutor(RemoteCommandOutcome.MissingCredential)).clone(profile(), request())
            assertEquals(RemoteGitCloneResult.MissingCredential, result)
        }

    @Test
    fun `untrusted host key is surfaced`() =
        runTest {
            val key = SshHostKey("ssh-ed25519", "ab".repeat(32))
            val result = RemoteGitCloner(FakeExecutor(RemoteCommandOutcome.HostKeyUntrusted(key))).clone(profile(), request())
            assertTrue(result is RemoteGitCloneResult.HostKeyUntrusted)
        }

    @Test
    fun `an invalid request is rejected before any execution`() =
        runTest {
            val executor = FakeExecutor(RemoteCommandOutcome.Completed("__TC_EXIT__0"))
            val result = RemoteGitCloner(executor).clone(profile(), request(path = "not-absolute"))

            assertTrue(result is RemoteGitCloneResult.Invalid)
            assertTrue((result as RemoteGitCloneResult.Invalid).errors.containsKey("remotePath"))
            assertNull(executor.lastScript)
        }

    @Test
    fun `the built script quotes the url and path and carries the markers`() =
        runTest {
            val executor = FakeExecutor(RemoteCommandOutcome.Completed("__TC_EXIT__0"))
            RemoteGitCloner(executor).clone(profile(), request())
            val script = executor.lastScript.orEmpty()
            assertTrue(script.contains("'/root/projects/TariffiaCode'"))
            assertTrue(script.contains(SshShellCommandExecutor.EXIT_MARKER))
            assertTrue(script.contains(SshShellCommandExecutor.NONEMPTY_MARKER))
        }
}
