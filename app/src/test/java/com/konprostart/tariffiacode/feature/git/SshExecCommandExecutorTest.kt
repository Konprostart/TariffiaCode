package com.konprostart.tariffiacode.feature.git

import com.konprostart.tariffiacode.core.ssh.SshAuth
import com.konprostart.tariffiacode.core.ssh.SshExecClient
import com.konprostart.tariffiacode.core.ssh.SshExecResult
import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.core.ssh.SshHostKeyVerifier
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshCredential
import com.konprostart.tariffiacode.data.ssh.SshCredentialCodec
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshExecCommandExecutorTest {
    private class FakeExecClient(
        private val result: SshExecResult,
    ) : SshExecClient {
        var command: String? = null
        var calls: Int = 0

        override suspend fun exec(
            host: String,
            port: Int,
            auth: SshAuth,
            verifier: SshHostKeyVerifier,
            command: String,
            timeoutMillis: Long,
            connectTimeoutMillis: Long,
        ): SshExecResult {
            this.command = command
            calls++
            return result
        }
    }

    private fun credentialStore(credential: SshCredential?): SshCredentialStore {
        val entries =
            if (credential == null) {
                emptyMap()
            } else {
                mapOf("ref" to SshCredentialCodec.encode(credential))
            }
        return SshCredentialStore(load = { entries }, save = {})
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
    fun `runs the command and returns the exit status from the control channel`() =
        runTest {
            val executor =
                SshExecCommandExecutor(
                    FakeExecClient(SshExecResult.Completed(exitCode = 0, stdout = "hello\n", stderr = "")),
                    credentialStore(SshCredential.Password("s")),
                )

            val outcome = executor.execute(profile(), "echo hi", 5_000)

            assertTrue(outcome is RemoteCommandOutcome.Completed)
            assertEquals(0, (outcome as RemoteCommandOutcome.Completed).exitCode)
            assertTrue(outcome.output.contains("hello"))
        }

    @Test
    fun `a non-zero exit status is reported as-is`() =
        runTest {
            val executor =
                SshExecCommandExecutor(
                    FakeExecClient(SshExecResult.Completed(exitCode = 128, stdout = "fatal\n", stderr = "")),
                    credentialStore(SshCredential.Password("s")),
                )

            val outcome = executor.execute(profile(), "git clone", 5_000) as RemoteCommandOutcome.Completed

            assertEquals(128, outcome.exitCode)
        }

    @Test
    fun `stdout that looks like the old sentinel cannot change the exit code`() =
        runTest {
            val executor =
                SshExecCommandExecutor(
                    FakeExecClient(SshExecResult.Completed(exitCode = 1, stdout = "__TC_EXIT__0\n", stderr = "")),
                    credentialStore(SshCredential.Password("s")),
                )

            val outcome = executor.execute(profile(), "false", 5_000) as RemoteCommandOutcome.Completed

            assertEquals("stdout must not forge the exit code", 1, outcome.exitCode)
        }

    @Test
    fun `stderr that looks like the old sentinel cannot change the exit code`() =
        runTest {
            val executor =
                SshExecCommandExecutor(
                    FakeExecClient(SshExecResult.Completed(exitCode = 1, stdout = "", stderr = "__TC_EXIT__0\n")),
                    credentialStore(SshCredential.Password("s")),
                )

            val outcome = executor.execute(profile(), "false", 5_000) as RemoteCommandOutcome.Completed

            assertEquals("stderr must not forge the exit code", 1, outcome.exitCode)
        }

    @Test
    fun `stdout and stderr are both kept in the output`() =
        runTest {
            val executor =
                SshExecCommandExecutor(
                    FakeExecClient(SshExecResult.Completed(exitCode = 0, stdout = "out", stderr = "err")),
                    credentialStore(SshCredential.Password("s")),
                )

            val outcome = executor.execute(profile(), "cmd", 5_000) as RemoteCommandOutcome.Completed

            assertTrue(outcome.output.contains("out"))
            assertTrue(outcome.output.contains("err"))
        }

    @Test
    fun `missing credential does not open a channel`() =
        runTest {
            val client = FakeExecClient(SshExecResult.Completed(0, "", ""))
            val executor = SshExecCommandExecutor(client, credentialStore(null))

            val outcome = executor.execute(profile(), "echo hi", 5_000)

            assertEquals(RemoteCommandOutcome.MissingCredential, outcome)
            assertEquals(0, client.calls)
        }

    @Test
    fun `an untrusted host key is surfaced`() =
        runTest {
            val key = SshHostKey("ssh-ed25519", "ab".repeat(32))
            val executor =
                SshExecCommandExecutor(
                    FakeExecClient(SshExecResult.HostKeyUntrusted(key)),
                    credentialStore(SshCredential.Password("s")),
                )

            val outcome = executor.execute(profile(), "echo hi", 5_000)

            assertTrue(outcome is RemoteCommandOutcome.HostKeyUntrusted)
        }

    @Test
    fun `a failure is surfaced`() =
        runTest {
            val executor =
                SshExecCommandExecutor(
                    FakeExecClient(SshExecResult.Failure("connection refused")),
                    credentialStore(SshCredential.Password("s")),
                )

            val outcome = executor.execute(profile(), "echo hi", 5_000)

            assertEquals("connection refused", (outcome as RemoteCommandOutcome.Failed).message)
        }
}
