package com.konprostart.tariffiacode.runtime.vps

import com.konprostart.tariffiacode.core.network.NetworkStatus
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
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
        var outcome: suspend (SshProfile) -> VpsPortForwardOutcome,
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
        recovery: VpsRecovery? = null,
    ): VpsTunnelController =
        VpsTunnelController(
            opener = opener::open,
            profileProvider = { profile },
            store = VpsTunnelStore(loadEnabled = { enabled }, saveEnabled = {}),
            scope = scope,
            profiles = profiles,
            work = work,
            recovery = recovery,
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
            assertTrue("the forward that was handed out is still held", first.isOpen)
            assertEquals("the second forward must never have been handed out", listOf(second), handedOut)
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

    @Test
    fun `stopping while the handshake is in flight closes the forward it was building`() =
        runTest {
            val handshake = CompletableDeferred<Unit>()
            val forward = FakeForward()
            val opener =
                Opener {
                    handshake.await()
                    VpsPortForwardOutcome.Connected("http://127.0.0.1:11434/", forward)
                }
            val work = RuntimeWorkTracker()
            val controller = controller(this, opener, work = work)

            controller.start()
            testScheduler.advanceUntilIdle()
            controller.stop()
            // The handshake now completes, behind the user's back.
            handshake.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertTrue("a tunnel the user turned off must not appear behind their back", forward.closed)
            assertEquals(VpsTunnelState.Stopped, controller.state.value)
            assertFalse(work.active.value)
        }

    @Test
    fun `a forward whose ssh session died is reopened rather than reported as open`() =
        runTest {
            val first = FakeForward()
            val second = FakeForward()
            val opener =
                Opener { p ->
                    if (p.id == "never") {
                        VpsPortForwardOutcome.Failed(
                            "unused",
                        )
                    } else {
                        VpsPortForwardOutcome.Connected("http://127.0.0.1:11434/", first)
                    }
                }
            val controller = controller(this, opener)

            controller.start()
            testScheduler.advanceUntilIdle()

            // The SSH session goes away without anything here being told: a NAT timeout or an sshd
            // idle timeout looks exactly like this.
            first.isOpen = false
            opener.outcome = { VpsPortForwardOutcome.Connected("http://127.0.0.1:11434/", second) }
            controller.start()
            testScheduler.advanceUntilIdle()

            assertEquals(2, opener.calls)
            assertTrue(second.isOpen)
            assertEquals(VpsTunnelState.Open("http://127.0.0.1:11434/"), controller.state.value)
        }

    @Test
    fun `a network handover rebuilds a tunnel that was up`() =
        runTest {
            val network = MutableStateFlow<NetworkStatus>(NetworkStatus.Connected("wifi"))
            val first = FakeForward()
            val second = FakeForward()
            var handed = 0
            val opener = Opener { VpsPortForwardOutcome.Connected("http://127.0.0.1:11434/", if (handed++ == 0) first else second) }
            // Its own scope on the shared scheduler: the network observer collects for as long as the
            // controller lives, which is exactly what `runTest` waits on before it can finish.
            val tunnelScope = TestScope(testScheduler)
            val controller = controller(tunnelScope, opener, recovery = VpsRecovery(scope = tunnelScope, networkStatus = network))
            testScheduler.advanceUntilIdle()

            controller.start()
            testScheduler.advanceUntilIdle()
            assertTrue(first.isOpen)

            network.value = NetworkStatus.Connected("cellular")
            testScheduler.advanceUntilIdle()

            assertTrue("the old socket is gone with the old network", first.closed)
            assertTrue(second.isOpen)
            assertEquals(VpsTunnelState.Open("http://127.0.0.1:11434/"), controller.state.value)
        }

    @Test
    fun `a handover retries rather than giving up on the first failure`() =
        runTest {
            val network = MutableStateFlow<NetworkStatus>(NetworkStatus.Connected("wifi"))
            val forward = FakeForward()
            var failNext = true
            val opener =
                Opener {
                    if (failNext) {
                        failNext = false
                        VpsPortForwardOutcome.Failed("network is not up yet")
                    } else {
                        VpsPortForwardOutcome.Connected("http://127.0.0.1:11434/", forward)
                    }
                }
            val tunnelScope = TestScope(testScheduler)
            val controller = controller(tunnelScope, opener, recovery = VpsRecovery(scope = tunnelScope, networkStatus = network))
            testScheduler.advanceUntilIdle()

            controller.start()
            testScheduler.advanceUntilIdle()
            failNext = true

            network.value = NetworkStatus.Connected("cellular")
            testScheduler.advanceUntilIdle()

            assertTrue("the first attempt after a handover routinely fails", forward.isOpen)
            assertEquals(VpsTunnelState.Open("http://127.0.0.1:11434/"), controller.state.value)
        }

    @Test
    fun `coming back to the foreground restores a tunnel the user asked to keep`() =
        runTest {
            val forward = FakeForward()
            val opener = Opener { VpsPortForwardOutcome.Connected("http://127.0.0.1:11434/", forward) }

            val off = controller(this, opener, enabled = false)
            off.onForeground()
            testScheduler.advanceUntilIdle()
            assertEquals("an unasked-for tunnel stays closed", 0, opener.calls)

            val on = controller(this, opener, enabled = true)
            on.onForeground()
            testScheduler.advanceUntilIdle()
            assertTrue(on.state.value is VpsTunnelState.Open)
        }

    @Test
    fun `the failure text comes from the caller rather than from the controller`() =
        runTest {
            val opener = Opener { VpsPortForwardOutcome.Connected("http://127.0.0.1:11434/", FakeForward()) }
            val controller =
                VpsTunnelController(
                    opener = opener::open,
                    profileProvider = { null },
                    store = VpsTunnelStore(loadEnabled = { false }, saveEnabled = {}),
                    scope = this,
                    noProfileMessage = { "nema SSH konekcije" },
                )

            controller.start()
            testScheduler.advanceUntilIdle()

            assertEquals(VpsTunnelState.Failed("nema SSH konekcije"), controller.state.value)
        }
}
