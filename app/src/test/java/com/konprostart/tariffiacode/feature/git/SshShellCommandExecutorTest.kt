package com.konprostart.tariffiacode.feature.git

import com.konprostart.tariffiacode.core.ssh.SshAuth
import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.core.ssh.SshHostKeyVerifier
import com.konprostart.tariffiacode.core.ssh.SshSession
import com.konprostart.tariffiacode.core.ssh.SshShellClient
import com.konprostart.tariffiacode.core.ssh.SshShellResult
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshCredential
import com.konprostart.tariffiacode.data.ssh.SshCredentialCodec
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshShellCommandExecutorTest {
    private class FakeSession(
        private val response: String,
    ) : SshSession {
        private val out = MutableSharedFlow<ByteArray>(replay = 1, extraBufferCapacity = 8)
        override val output: Flow<ByteArray> = out
        var written: String = ""
        var closed: Boolean = false
        override val isOpen: Boolean get() = !closed

        override fun writeStandardInput(bytes: ByteArray) {
            written += bytes.decodeToString()
            if (response.isNotEmpty()) out.tryEmit(response.toByteArray())
        }

        override fun resize(
            columns: Int,
            rows: Int,
        ) = Unit

        override fun close() {
            closed = true
        }
    }

    private class FakeShellClient(
        private val result: SshShellResult,
    ) : SshShellClient {
        override suspend fun openShell(
            host: String,
            port: Int,
            auth: SshAuth,
            verifier: SshHostKeyVerifier,
            ptyType: String,
            connectTimeoutMillis: Long,
        ): SshShellResult = result
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
    fun `runs the script and returns collected output`() =
        runTest {
            val session = FakeSession("hello\n__TC_EXIT__0\n")
            val executor =
                SshShellCommandExecutor(
                    FakeShellClient(SshShellResult.Opened(session)),
                    credentialStore(SshCredential.Password("s")),
                )

            val outcome = executor.execute(profile(), "echo hi; echo __TC_EXIT__\$?", 5_000)

            assertTrue(outcome is RemoteCommandOutcome.Completed)
            assertTrue((outcome as RemoteCommandOutcome.Completed).output.contains("__TC_EXIT__0"))
            assertTrue(session.written.contains("echo hi"))
            assertTrue("session must be closed after the command", session.closed)
        }

    @Test
    fun `missing credential does not open a shell`() =
        runTest {
            val session = FakeSession("")
            val executor = SshShellCommandExecutor(FakeShellClient(SshShellResult.Opened(session)), credentialStore(null))

            val outcome = executor.execute(profile(), "echo hi", 5_000)

            assertEquals(RemoteCommandOutcome.MissingCredential, outcome)
            assertFalse(session.closed)
            assertEquals("", session.written)
        }

    @Test
    fun `an untrusted host key is surfaced`() =
        runTest {
            val key = SshHostKey("ssh-ed25519", "ab".repeat(32))
            val executor =
                SshShellCommandExecutor(
                    FakeShellClient(SshShellResult.HostKeyUntrusted(key)),
                    credentialStore(SshCredential.Password("s")),
                )

            val outcome = executor.execute(profile(), "echo hi", 5_000)

            assertTrue(outcome is RemoteCommandOutcome.HostKeyUntrusted)
        }

    @Test
    fun `a shell failure is surfaced`() =
        runTest {
            val executor =
                SshShellCommandExecutor(
                    FakeShellClient(SshShellResult.Failure("connection refused")),
                    credentialStore(SshCredential.Password("s")),
                )

            val outcome = executor.execute(profile(), "echo hi", 5_000)

            assertEquals("connection refused", (outcome as RemoteCommandOutcome.Failed).message)
        }
}
