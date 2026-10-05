package com.konprostart.tariffiacode.feature.terminal

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteTerminalViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeSession : SshSession {
        val input = StringBuilder()
        private val out = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
        override val output: Flow<ByteArray> = out
        private var open = true
        override val isOpen: Boolean get() = open

        override fun writeStandardInput(bytes: ByteArray) {
            input.append(bytes.decodeToString())
        }

        override fun resize(
            columns: Int,
            rows: Int,
        ) = Unit

        override fun close() {
            open = false
        }

        suspend fun emit(bytes: ByteArray) = out.emit(bytes)
    }

    private class FakeShellClient(
        var result: SshShellResult,
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

    private fun profile(trusted: String? = null) =
        SshProfile(
            id = "vps-1",
            name = "My VPS",
            host = "example.com",
            port = 22,
            username = "root",
            authType = SshAuthType.PASSWORD,
            credentialRef = "ref",
            trustedHostKeyFingerprint = trusted,
        )

    @Test
    fun `connect success opens the shell and streams output`() =
        runTest {
            val session = FakeSession()
            val vm = RemoteTerminalViewModel(FakeShellClient(SshShellResult.Opened(session)), credentialStore(SshCredential.Password("s")), { profile() })

            vm.connect()

            assertTrue(vm.state.value.isOpen)
            assertEquals("root@example.com", vm.state.value.hostLabel)
            session.emit("welcome\n".toByteArray())
            assertTrue(vm.state.value.output.contains("welcome"))
            vm.sendLine("ls")
            assertTrue(session.input.contains("ls\n"))
            vm.disconnect()
            assertFalse(vm.state.value.isOpen)
        }

    @Test
    fun `missing profile reports an error without opening`() =
        runTest {
            val vm = RemoteTerminalViewModel(FakeShellClient(SshShellResult.Failure("x")), credentialStore(SshCredential.Password("s")), { null })
            vm.connect()
            assertNotNull(vm.state.value.error)
            assertFalse(vm.state.value.isOpen)
        }

    @Test
    fun `missing credential reports an error`() =
        runTest {
            val vm = RemoteTerminalViewModel(FakeShellClient(SshShellResult.Failure("x")), credentialStore(null), { profile() })
            vm.connect()
            assertNotNull(vm.state.value.error)
            assertFalse(vm.state.value.isOpen)
        }

    @Test
    fun `failed shell surfaces the failure`() =
        runTest {
            val vm = RemoteTerminalViewModel(FakeShellClient(SshShellResult.Failure("connection refused")), credentialStore(SshCredential.Password("s")), { profile() })
            vm.connect()
            assertEquals("connection refused", vm.state.value.error)
            assertFalse(vm.state.value.isOpen)
        }

    @Test
    fun `untrusted host key surfaces an error`() =
        runTest {
            val vm =
                RemoteTerminalViewModel(
                    FakeShellClient(SshShellResult.HostKeyUntrusted(SshHostKey("ssh-ed25519", "ab".repeat(32)))),
                    credentialStore(SshCredential.Password("s")),
                    { profile() },
                )
            vm.connect()
            assertTrue(vm.state.value.error.orEmpty().contains("host key"))
            assertFalse(vm.state.value.isOpen)
        }

    @Test
    fun `reconnect opens a fresh shell`() =
        runTest {
            val first = FakeSession()
            val second = FakeSession()
            val client = FakeShellClient(SshShellResult.Opened(first))
            val vm = RemoteTerminalViewModel(client, credentialStore(SshCredential.Password("s")), { profile() })

            vm.connect()
            assertTrue(vm.state.value.isOpen)
            vm.disconnect()
            assertFalse(vm.state.value.isOpen)

            client.result = SshShellResult.Opened(second)
            vm.connect()
            assertTrue(vm.state.value.isOpen)
            vm.disconnect()
        }
}
