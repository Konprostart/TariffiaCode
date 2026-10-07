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
            val executor = FakeExecutor(RemoteCommandOutcome.Completed(0, "Cloning into 'TariffiaCode'...\n"))
            val result = RemoteGitCloner(executor).clone(profile(), request())

            assertTrue(result is RemoteGitCloneResult.Success)
            assertTrue((result as RemoteGitCloneResult.Success).output.contains("Cloning into"))
            assertTrue(executor.lastScript.orEmpty().contains("git clone --depth 1"))
        }

    @Test
    fun `an existing non-empty target is refused`() =
        runTest {
            val executor = FakeExecutor(RemoteCommandOutcome.Completed(RemoteGitCloner.TARGET_NOT_EMPTY_EXIT, ""))
            val result = RemoteGitCloner(executor).clone(profile(), request())
            assertEquals(RemoteGitCloneResult.TargetNotEmpty, result)
        }

    @Test
    fun `a non-zero exit is a clone failure`() =
        runTest {
            val executor = FakeExecutor(RemoteCommandOutcome.Completed(128, "fatal: repository not found\n"))
            val result = RemoteGitCloner(executor).clone(profile(), request())
            assertTrue(result is RemoteGitCloneResult.Failed)
            assertEquals("git clone failed (exit 128)", (result as RemoteGitCloneResult.Failed).message)
        }

    @Test
    fun `a sentinel-like success line in the output does not turn a failure into a success`() =
        runTest {
            val executor = FakeExecutor(RemoteCommandOutcome.Completed(128, "__TC_EXIT__0\nfatal: repository not found\n"))
            val result = RemoteGitCloner(executor).clone(profile(), request())
            assertTrue(result is RemoteGitCloneResult.Failed)
            assertEquals("git clone failed (exit 128)", (result as RemoteGitCloneResult.Failed).message)
        }

    @Test
    fun `a sentinel-like failure line in the output does not turn a success into a failure`() =
        runTest {
            val executor = FakeExecutor(RemoteCommandOutcome.Completed(0, "__TC_EXIT__128\nCloning into 'TariffiaCode'...\n"))
            val result = RemoteGitCloner(executor).clone(profile(), request())
            assertTrue(result is RemoteGitCloneResult.Success)
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
            val executor = FakeExecutor(RemoteCommandOutcome.Completed(0, ""))
            val result = RemoteGitCloner(executor).clone(profile(), request(path = "not-absolute"))

            assertTrue(result is RemoteGitCloneResult.Invalid)
            assertTrue((result as RemoteGitCloneResult.Invalid).errors.containsKey("remotePath"))
            assertNull(executor.lastScript)
        }

    @Test
    fun `the built script quotes the url and path and carries no spoofable marker`() =
        runTest {
            val executor = FakeExecutor(RemoteCommandOutcome.Completed(0, ""))
            RemoteGitCloner(executor).clone(profile(), request())
            val script = executor.lastScript.orEmpty()
            assertTrue(script.contains("'/root/projects/TariffiaCode'"))
            assertTrue(
                "non-empty target is signalled by an exit code, not a marker",
                script.contains("exit ${RemoteGitCloner.TARGET_NOT_EMPTY_EXIT}"),
            )
            assertTrue("no fixed exit marker", !script.contains("__TC_EXIT__"))
            assertTrue("no fixed non-empty marker", !script.contains("__TC_NONEMPTY__"))
        }
}
