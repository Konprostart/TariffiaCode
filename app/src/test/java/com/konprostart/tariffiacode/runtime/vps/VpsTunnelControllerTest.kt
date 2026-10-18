package com.konprostart.tariffiacode.runtime.vps

import com.konprostart.tariffiacode.core.runtime.RuntimeWorkTracker
import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.core.ssh.SshPortForward
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshCredential
import com.konprostart.tariffiacode.data.ssh.SshCredentialCodec
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.data.ssh.SshProfileStore
import com.konprostart.tariffiacode.data.vps.VpsTunnelStore
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpsTunnelControllerTest {
    private class FakeForward(
        override val localPort: Int = 11434,
    ) : SshPortForward {
        override val localHost = "127.0.0.1"
        override val remoteHost = "127.0.0.1"
        override val remotePort = 11434
        override var isOpen = true
        var closed = false

        override fun close() {
            closed = true
            isOpen = false
        }
    }

    private val profile =
        SshProfile(
            id = "vps-1",
            name = "VPS",
            host = "example.com",
            username = "root",
            authType = SshAuthType.PASSWORD,
            credentialRef = "ref",
        )

    /** Counts the forwards the controller hands out, and hands back whatever the test asked for. */
    private class Opener(
        var outcome: (SshProfile) -> VpsPortForwardOutcome,
    ) {
        var calls = 0

        suspend fun open(p: SshProfile): VpsPortForwardOutcome {
            calls++
            return outcome(p)
        }
    }

    private fun profileStore(): SshProfileStore {
        val entries = mapOf("ref" to SshCredentialCodec.encode(SshCredential.Password("hunter2")))
        val credentials = SshCredentialStore(load = { entries }, save = {})
        val saved = mutableListOf<SshProfile>()
        return SshProfileStore(load = { saved }, save = {
            saved.clear()
            saved.addAll(it)
        }, credentials = credentials)
    }

    private fun controller(
        scope: TestScope,
        opener: Opener,
        profiles: SshProfileStore? = null,
        enabled: Boolean = false,
        work: RuntimeWorkTracker? = null,
    ): VpsTunnelController =
        VpsTunnelController(
            opener = opener::open,
            profileProvider = { profile },
            store = VpsTunnelStore(loadEnabled = { enabled }, saveEnabled = {}),
            scope = scope,
            profiles = profiles,
            work = work,
        )

    @Test
    fun `a start holds a forward and reports the loopback url`() =
        runTest {
            val forward = FakeForward()
            val opener = Opener { VpsPortForwardOutcome.Connected("http://127.0.0.1:11434/", forward) }
            val controller = controller(this, opener)

            controller.start()
            testScheduler.advanceUntilIdle()

            assertEquals(1, opener.calls)
            assertEquals(VpsTunnelState.Open("http://127.0.0.1:11434/"), controller.state.value)
        }

    @Test
    fun `starting twice keeps one forward rather than leaking a second`() =
        runTest {
            val first = FakeForward()
            val second = FakeForward()
            val handedOut = mutableListOf<FakeForward>()
            val opener = Opener { VpsPortForwardOutcome.Connected("http://127.0.0.1:11434/", handedOut.removeAt(0)) }
            handedOut.addAll(listOf(first, second))
            val controller = controller(this, opener)

            controller.start()
            testScheduler.advanceUntilIdle()
            controller.start()
            testScheduler.advanceUntilIdle()

            assertEquals(1, opener.calls)
            assertTrue("the first forward is still held", first.isOpen)
            assertFalse("the second forward must never be opened", second.isOpen)
        }

    @Test
    fun `stopping closes the forward and releases the wake lock`() =
        runTest {
            val forward = FakeForward()
            val opener = Opener { VpsPortForwardOutcome.Connected("http://127.0.0.1:11434/", forward) }
            val work = RuntimeWorkTracker()
            val controller = controller(this, opener, work = work)

            controller.start()
            testScheduler.advanceUntilIdle()
            assertTrue("an open tunnel must hold the device awake", work.active.value)

            controller.stop()

            assertTrue(forward.closed)
            assertEquals(VpsTunnelState.Stopped, controller.state.value)
            assertFalse(work.active.value)
        }

    @Test
    fun `stopping twice is not an error`() =
        runTest {
            val forward = FakeForward()
            val opener = Opener { VpsPortForwardOutcome.Connected("http://127.0.0.1:11434/", forward) }
            val controller = controller(this, opener)

            controller.start()
            testScheduler.advanceUntilIdle()
            controller.stop()
            controller.stop()

            assertEquals(VpsTunnelState.Stopped, controller.state.value)
        }

    @Test
    fun `no ssh connection selected fails with something the user can act on`() =
        runTest {
            val opener = Opener { VpsPortForwardOutcome.Connected("http://127.0.0.1:11434/", FakeForward()) }
            val controller =
                VpsTunnelController(
                    opener = opener::open,
                    profileProvider = { null },
                    store = VpsTunnelStore(loadEnabled = { false }, saveEnabled = {}),
                    scope = this,
                )

            controller.start()
            testScheduler.advanceUntilIdle()

            assertTrue(controller.state.value is VpsTunnelState.Failed)
        }

    @Test
    fun `a failure to open takes no wake lock with it`() =
        runTest {
            val work = RuntimeWorkTracker()
            val opener = Opener { VpsPortForwardOutcome.Failed("connection refused") }
            val controller = controller(this, opener, work = work)

            controller.start()
            testScheduler.advanceUntilIdle()

            assertEquals(VpsTunnelState.Failed("connection refused"), controller.state.value)
            assertFalse("a tunnel that is not up must not hold the device awake", work.active.value)
        }

    @Test
    fun `an untrusted host key is surfaced rather than retried behind the user's back`() =
        runTest {
            val key = SshHostKey("ssh-ed25519", "ab".repeat(32))
            val opener = Opener { VpsPortForwardOutcome.NeedsHostKeyTrust(key) }
            val controller = controller(this, opener)

            controller.start()
            testScheduler.advanceUntilIdle()

            assertEquals(key, controller.pendingHostKey.value)
            assertEquals(VpsTunnelState.Stopped, controller.state.value)
            assertEquals(1, opener.calls)
        }

    @Test
    fun `trusting the key persists it and retries`() =
        runTest {
            val store = profileStore()
            val key = SshHostKey("ssh-ed25519", "ab".repeat(32))
            val forward = FakeForward()
            val opener =
                Opener { p ->
                    if (p.trustedHostKeyFingerprint == key.sha256Fingerprint) {
                        VpsPortForwardOutcome.Connected("http://127.0.0.1:11434/", forward)
                    } else {
                        VpsPortForwardOutcome.NeedsHostKeyTrust(key)
                    }
                }
            val controller = controller(this, opener, profiles = store)

            controller.start()
            testScheduler.advanceUntilIdle()
            val trusted = controller.trustPendingHostKey()
            testScheduler.advanceUntilIdle()

            assertEquals(key.sha256Fingerprint, trusted?.trustedHostKeyFingerprint)
            assertEquals(key.sha256Fingerprint, store.profile("vps-1")?.trustedHostKeyFingerprint)
            assertNull(controller.pendingHostKey.value)
            assertTrue(controller.state.value is VpsTunnelState.Open)
        }

    @Test
    fun `dismissing the key leaves the tunnel down until it is asked for again`() =
        runTest {
            val key = SshHostKey("ssh-ed25519", "ab".repeat(32))
            val opener = Opener { VpsPortForwardOutcome.NeedsHostKeyTrust(key) }
            val controller = controller(this, opener)

            controller.start()
            testScheduler.advanceUntilIdle()
            controller.dismissHostKey()

            assertNull(controller.pendingHostKey.value)
            assertEquals(1, opener.calls)
        }

    @Test
    fun `the router starting opens the tunnel only when the user asked for it`() =
        runTest {
            val forward = FakeForward()
            val opener = Opener { VpsPortForwardOutcome.Connected("http://127.0.0.1:11434/", forward) }

            val off = controller(this, opener, enabled = false)
            off.onRouterStarted()
            testScheduler.advanceUntilIdle()
            assertEquals("an unasked-for tunnel must stay closed", 0, opener.calls)

            val on = controller(this, opener, enabled = true)
            on.onRouterStarted()
            testScheduler.advanceUntilIdle()
            assertEquals(1, opener.calls)
            assertTrue(on.state.value is VpsTunnelState.Open)
        }

    @Test
    fun `turning it off remembers the choice and closes the tunnel`() =
        runTest {
            val forward = FakeForward()
            val opener = Opener { VpsPortForwardOutcome.Connected("http://127.0.0.1:11434/", forward) }
            var enabled = true
            val controller =
                VpsTunnelController(
                    opener = opener::open,
                    profileProvider = { profile },
                    store = VpsTunnelStore(loadEnabled = { enabled }, saveEnabled = { enabled = it }),
                    scope = this,
                )

            controller.start()
            testScheduler.advanceUntilIdle()
            controller.setEnabled(false)

            assertFalse("the choice has to outlive the process", enabled)
            assertTrue("the forward the user was holding has to go down with the switch", forward.closed)
            assertEquals(VpsTunnelState.Stopped, controller.state.value)
        }
}
