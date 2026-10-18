package com.konprostart.tariffiacode.runtime.ssh

import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.core.ssh.SshSession
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshHeldSessionStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.data.ssh.SshProfileStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions for the settings screen's persistent connection: the holder must keep the session
 * alive once it asked nothing about the screen, close what it replaces, and never install the
 * session a disconnect happened inside of.
 */
class SshSessionHolderTest {
    private class FakeSession : SshSession {
        var closed = false

        override val output: Flow<ByteArray> = emptyFlow()

        override val isOpen: Boolean
            get() = !closed

        override fun writeStandardInput(bytes: ByteArray) {}

        override fun resize(
            columns: Int,
            rows: Int,
        ) {}

        override fun close() {
            closed = true
        }
    }

    private fun profile() =
        SshProfile(
            id = "p1",
            name = "VPS",
            host = "example.com",
            username = "root",
            authType = SshAuthType.PASSWORD,
            credentialRef = "ref",
        )

    private fun holder(
        profile: SshProfile,
        scheduler: TestCoroutineScheduler,
        opener: suspend (SshProfile) -> SshConnectionOutcome,
        remembered: MutableList<String?> = mutableListOf(),
    ): SshSessionHolder =
        SshSessionHolder(
            opener = opener,
            profiles =
                SshProfileStore(
                    load = { listOf(profile) },
                    save = {},
                    credentials = SshCredentialStore(load = { emptyMap() }, save = {}),
                ),
            selection = SshHeldSessionStore({ remembered.lastOrNull() }, { remembered.add(it) }),
            scope = CoroutineScope(StandardTestDispatcher(scheduler)),
        )

    @Test
    fun `a connect outlives the screen and a disconnect is the thing that stops it`() =
        runTest {
            val remembered = mutableListOf<String?>()
            val session = FakeSession()
            val holder =
                holder(profile(), testScheduler, { SshConnectionOutcome.Connected(session) }, remembered)

            holder.connect("p1")
            testScheduler.advanceUntilIdle()

            assertEquals(HeldSshConnection.Connected("p1"), holder.state.value)
            assertEquals("p1", holder.heldProfileId)
            assertFalse(session.closed)
            assertEquals("p1", remembered.lastOrNull())

            holder.disconnect()
            testScheduler.advanceUntilIdle()

            assertTrue(session.closed)
            assertEquals(HeldSshConnection.Idle, holder.state.value)
            assertNull(remembered.lastOrNull())
        }

    @Test
    fun `asking for a different connection closes the session it replaces`() =
        runTest {
            val first = FakeSession()
            val second = FakeSession()
            var calls = 0
            val holder =
                holder(
                    profile(),
                    testScheduler,
                    { if (calls++ == 0) SshConnectionOutcome.Connected(first) else SshConnectionOutcome.Connected(second) },
                )

            holder.connect("p1")
            testScheduler.advanceUntilIdle()
            holder.connect("p1")
            testScheduler.advanceUntilIdle()

            assertTrue("the replaced session must be closed, not orphaned", first.closed)
            assertFalse(second.closed)
            assertEquals(HeldSshConnection.Connected("p1"), holder.state.value)
        }

    @Test
    fun `a disconnect before the attempt starts stops it before the transport is touched`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var attempts = 0
            val session = FakeSession()
            val holder =
                holder(profile(), testScheduler, {
                    attempts++
                    gate.await()
                    SshConnectionOutcome.Connected(session)
                })

            // The attempt is queued but has not been dispatched yet.
            holder.connect("p1")
            holder.disconnect()
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertEquals("a cancelled queued attempt must not reach the transport at all", 0, attempts)
            assertFalse(session.closed)
            assertEquals(HeldSshConnection.Idle, holder.state.value)
        }

    @Test
    fun `a disconnect while the handshake is open closes the session it was reaching for`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val opened = FakeSession()
            val holder =
                holder(profile(), testScheduler, {
                    gate.await()
                    SshConnectionOutcome.Connected(opened)
                })

            holder.connect("p1")
            testScheduler.advanceUntilIdle()
            assertEquals(HeldSshConnection.Connecting("p1"), holder.state.value)

            holder.disconnect()
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertTrue("the session built during the handshake must not survive the disconnect", opened.closed)
            assertEquals(HeldSshConnection.Idle, holder.state.value)
        }

    @Test
    fun `a session that quietly dies is brought back up on the next restore`() =
        runTest {
            val remembered = mutableListOf<String?>()
            val first = FakeSession()
            val second = FakeSession()
            var calls = 0
            val holder =
                holder(
                    profile(),
                    testScheduler,
                    { if (calls++ == 0) SshConnectionOutcome.Connected(first) else SshConnectionOutcome.Connected(second) },
                    remembered,
                )

            holder.connect("p1")
            testScheduler.advanceUntilIdle()
            assertEquals(HeldSshConnection.Connected("p1"), holder.state.value)

            // The network goes away with nobody watching; only the next event notices.
            first.isOpen = false
            holder.restoreIfRemembered()
            testScheduler.advanceUntilIdle()

            assertTrue(first.closed)
            assertEquals(HeldSshConnection.Connected("p1"), holder.state.value)
            assertEquals("p1", holder.heldProfileId)
        }

    @Test
    fun `restore does nothing while a session is already held or opening`() =
        runTest {
            val session = FakeSession()
            var learned = 0
            val holder =
                holder(profile(), testScheduler, {
                    learned++
                    SshConnectionOutcome.Connected(session)
                })

            holder.connect("p1")
            testScheduler.advanceUntilIdle()
            holder.restoreIfRemembered()
            testScheduler.advanceUntilIdle()

            assertEquals(1, learned)
            assertEquals(HeldSshConnection.Connected("p1"), holder.state.value)
        }

    @Test
    fun `an untrusted host key is reported and the trust retry opens the tunnel`() =
        runTest {
            val key = SshHostKey("ssh-ed25519", "ab".repeat(32))
            val session = FakeSession()
            var calls = 0
            val holder =
                holder(
                    profile(),
                    testScheduler,
                    { if (calls++ == 0) SshConnectionOutcome.NeedsHostKeyTrust(key) else SshConnectionOutcome.Connected(session) },
                )

            holder.connect("p1")
            testScheduler.advanceUntilIdle()

            val needs = holder.state.value as HeldSshConnection.NeedsTrust
            assertEquals("p1", needs.profileId)
            assertEquals(key, needs.hostKey)
            assertFalse(needs.mismatch)

            holder.connect("p1")
            testScheduler.advanceUntilIdle()

            assertEquals(HeldSshConnection.Connected("p1"), holder.state.value)
        }

    @Test
    fun `a transport that throws surfaces as a failure instead of escaping the holder`() =
        runTest {
            val holder =
                holder(
                    profile(),
                    testScheduler,
                    { throw NoClassDefFoundError("javax/security/auth/login/CredentialException") },
                )

            holder.connect("p1")
            testScheduler.advanceUntilIdle()

            val failed = holder.state.value as HeldSshConnection.Failed
            assertTrue(failed.message.contains("NoClassDefFoundError"))
            assertTrue(failed.message.contains("javax/security/auth/login/CredentialException"))
        }
}
