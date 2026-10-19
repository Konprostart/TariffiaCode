package com.konprostart.tariffiacode.runtime.vps

import android.util.Log
import com.konprostart.tariffiacode.core.network.NetworkStatus
import com.konprostart.tariffiacode.core.network.shouldRebuildTunnel
import com.konprostart.tariffiacode.core.runtime.RuntimeWorkTracker
import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.core.ssh.SshPortForward
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.data.ssh.SshProfileStore
import com.konprostart.tariffiacode.data.vps.VpsTunnelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** What the background tunnel is doing, as the screen shows it. */
sealed interface VpsTunnelState {
    /** No forward is held. */
    data object Stopped : VpsTunnelState

    /** A forward is being asked for. */
    data object Opening : VpsTunnelState

    /** The forward is up; [localUrl] is the loopback address it is reachable on. */
    data class Open(
        val localUrl: String,
    ) : VpsTunnelState

    /** The last attempt did not work, and why. */
    data class Failed(
        val message: String,
    ) : VpsTunnelState
}

/**
 * Holds the VPS port forward for as long as the app process lives, rather than for as long as a
 * screen does.
 *
 * It exists because of who used to own the forward: Live Preview opened it, so leaving that screen
 * - or the app - closed it, and an agent reaching an Ollama on the VPS found nothing listening. A
 * forward that only lives while a window is open cannot be depended on by anything that runs in the
 * background, which is exactly what the router is.
 *
 * Three things can open it, and all three come through here:
 *  - the user turning it on, or pressing start, from the router screen;
 *  - the router being started, when the user asked for it to be kept open ([onRouterStarted]);
 *  - the app coming back to the foreground after process death ([onForeground]), because the process
 *    is the thing that holds the forward and process death is the one way it silently goes away.
 *
 * Recovery is optional, as it is for [VpsRuntimeTarget]: a controller built without [recovery]
 * behaves exactly as before. With it, a Wi-Fi <-> cellular handover rebuilds a tunnel that was up -
 * that is precisely when the socket behind it is dead and nothing else would notice - with the same
 * bounded backoff the VPS runtime uses, because the first attempt after a handover routinely lands
 * before the new network is usable.
 *
 * The opener is a suspend lambda rather than [VpsPortForwardManager] so the whole lifecycle can be
 * driven in a unit test without an SSH server; the app passes a lambda that calls the manager.
 *
 * The two failure messages are injected with English defaults rather than hard-coded strings: this
 * class is not Android code and its messages end up on a screen, so the caller that has a Context
 * supplies the localised text.
 */
class VpsTunnelController(
    private val opener: suspend (SshProfile) -> VpsPortForwardOutcome,
    private val profileProvider: () -> SshProfile?,
    private val store: VpsTunnelStore,
    private val scope: CoroutineScope,
    private val profiles: SshProfileStore? = null,
    private val work: RuntimeWorkTracker? = null,
    private val recovery: VpsRecovery? = null,
    private val noProfileMessage: () -> String = { "Select an SSH connection for the VPS first" },
    private val noCredentialMessage: () -> String = { "No stored SSH credential for that connection" },
) {
    private val _state = MutableStateFlow<VpsTunnelState>(VpsTunnelState.Stopped)
    val state: StateFlow<VpsTunnelState> = _state.asStateFlow()

    private val _pendingHostKey = MutableStateFlow<SshHostKey?>(null)
    val pendingHostKey: StateFlow<SshHostKey?> = _pendingHostKey.asStateFlow()

    /** Held for as long as the forward is, so the device may not suspend under an agent mid-turn. */
    @Volatile
    private var lease: RuntimeWorkTracker.Lease? = null

    @Volatile
    private var forward: SshPortForward? = null

    /** One open at a time: a second start while the first is in flight must not leak a forward. */
    private val opening = AtomicBoolean(false)

    /** Bumped by [stop] so an open that finishes afterwards knows it was cancelled. */
    private val epoch = AtomicLong(0)

    /** One recovery loop at a time, however many handovers arrive while it is running. */
    private val recovering = AtomicBoolean(false)

    init {
        observeNetwork()
    }

    /** True when the user asked for this tunnel to be kept open. */
    fun isEnabled(): Boolean = store.isEnabled()

    /** Turn the tunnel on or off, and honour it right now rather than at the next trigger. */
    fun setEnabled(enabled: Boolean) {
        store.setEnabled(enabled)
        if (enabled) start() else stop()
    }

    /** Open the forward unless it is already up or being opened. Safe to call repeatedly. */
    fun start() {
        scope.launch { open() }
    }

    /**
     * Close the forward and let the device sleep again.
     *
     * Idempotent, and safe from any thread: closing a forward nobody holds is not an error the
     * caller has to handle.
     */
    fun stop() {
        // Before the close, not after: an open that is still in its SSH handshake has to notice it
        // was cancelled, or it installs the forward it was building moments after the user turned
        // this off - and nothing would then hold it but the process itself.
        epoch.incrementAndGet()
        closeHeldForward()
        releaseLease()
        // A key the user has not accepted belongs to a tunnel they just stopped; leaving it on the
        // screen invites them to press "Trust and retry" on something that is no longer there.
        _pendingHostKey.value = null
        _state.value = VpsTunnelState.Stopped
    }

    /**
     * Drops the forward without announcing that the tunnel is gone.
     *
     * Used while rebuilding one: the tunnel was up a moment ago, still belongs to the user, and is
     * coming back in a second or two - so publishing a stop for it would take the keep-alive service
     * down and then try to start it again from the background, which Android 12+ refuses.
     */
    private fun closeHeldForward() {
        val held = forward
        forward = null
        if (held != null) runCatching { held.close() }
    }

    /** Called when the router is started: what the user asked for should be up before an agent dials in. */
    fun onRouterStarted() {
        if (store.isEnabled()) start()
    }

    /** Called when the app comes back to the foreground; process death is how a held forward is lost. */
    fun onForeground() {
        if (store.isEnabled()) start()
    }

    /**
     * Called when a connection becomes the selected one for the VPS.
     *
     * Turning the tunnel on before a profile is chosen is a normal order of events across the
     * settings screens, and it leaves the controller Failed on "Select an SSH connection for the
     * VPS first" the moment the user then chooses one. Calling this after a selection re-runs
     * [start] for a tunnel the user asked for, which closes that gap; [start] is idempotent, so an
     * already-open tunnel is untouched.
     */
    fun onProfileSelected() {
        if (store.isEnabled()) start()
    }

    /** Persist the key the user confirmed, then try again with it. Returns the profile that was updated. */
    fun trustPendingHostKey(): SshProfile? {
        val pending = _pendingHostKey.value ?: return null
        val profile = profileProvider() ?: return null
        val profileStore = profiles ?: return null
        val trusted = profile.trusting(pending.sha256Fingerprint)
        profileStore.upsert(trusted)
        _pendingHostKey.value = null
        // Retried with the profile that was just trusted rather than through [profileProvider]:
        // whoever supplies it holds a snapshot of the selection, so it is still the untrusted copy
        // and the retry would refuse the very key the user just accepted.
        scope.launch { open(trusted) }
        return trusted
    }

    /** The user declined the key; the tunnel stays down until they ask again. */
    fun dismissHostKey() {
        _pendingHostKey.value = null
    }

    private suspend fun open(trustedProfile: SshProfile? = null) {
        // `isOpen`, not merely non-null: a forward whose SSH session died behind our back is still a
        // held object, and treating it as one leaves a "tunnel is open" screen and notification over
        // a port nothing answers on until the user happens to stop and start it by hand.
        if (forward?.isOpen == true || _state.value is VpsTunnelState.Opening) return
        if (!opening.compareAndSet(false, true)) return
        val started = epoch.get()
        try {
            _state.value = VpsTunnelState.Opening
            // Re-read from the store rather than trusting the selection snapshot: trusting a host key
            // writes the profile, and the selection the caller holds still says the old, untrusted
            // copy - so every later rebuild would ask the user for the same key again.
            val profile = trustedProfile ?: profileProvider()?.let { selected -> profiles?.profile(selected.id) ?: selected }
            if (profile == null) {
                _state.value = VpsTunnelState.Failed(noProfileMessage())
                return
            }
            val outcome = opener(profile)
            if (epoch.get() != started) {
                // Cancelled while the handshake was in flight. The forward this attempt built is one
                // the user has already asked to be gone, so it is closed here instead of installed.
                if (outcome is VpsPortForwardOutcome.Connected) runCatching { outcome.forward.close() }
                return
            }
            when (outcome) {
                is VpsPortForwardOutcome.Connected -> {
                    forward = outcome.forward
                    // Taken only once a forward is actually held: a lease for an attempt that failed
                    // would keep the device awake for a tunnel that does not exist.
                    lease = work?.acquire(WAKE_LOCK_TAG)
                    _state.value = VpsTunnelState.Open(outcome.localUrl)
                    // Re-checked after installing, not only before: a stop() that lands between that
                    // check and here found no forward to close and moved on, and without this the
                    // tunnel the user just turned off would be installed behind their back.
                    if (epoch.get() != started) {
                        forward = null
                        releaseLease()
                        runCatching { outcome.forward.close() }
                        _state.value = VpsTunnelState.Stopped
                    }
                }

                is VpsPortForwardOutcome.NeedsHostKeyTrust -> {
                    _pendingHostKey.value = outcome.hostKey
                    _state.value = VpsTunnelState.Stopped
                }

                VpsPortForwardOutcome.MissingCredential -> _state.value = VpsTunnelState.Failed(noCredentialMessage())
                is VpsPortForwardOutcome.Failed -> _state.value = VpsTunnelState.Failed(outcome.message)
            }
        } finally {
            opening.set(false)
        }
    }

    private fun releaseLease() {
        lease?.release()
        lease = null
    }

    /**
     * Rebuild a tunnel the user believes is up after the network handed over underneath it.
     *
     * Gated on the forward rather than on the connection being healthy: a handover is exactly the
     * case where it is not, so waiting for it to look healthy is how this goes unrecovered. The
     * first emission is a baseline, never a trigger - the monitor delivers the current network on
     * registration, and treating that as a handover would tear down a healthy tunnel.
     */
    private fun observeNetwork() {
        val recovery = recovery ?: return
        recovery.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var previous: NetworkStatus = NetworkStatus.Unknown
            var seeded = false
            recovery.networkStatus.collect { current ->
                val handover = seeded && shouldRebuildTunnel(previous, current)
                previous = current
                seeded = true
                if (handover && forward != null) scheduleReconnect(recovery)
            }
        }
    }

    /**
     * Retries with the same bounded backoff [VpsRuntimeTarget] uses, for the same reason: the first
     * attempt after a handover routinely lands before the new network is usable, and without a retry
     * loop the tunnel would stay down until the next foreground - which is minutes away for an agent
     * that is using the router right now.
     */
    private fun scheduleReconnect(recovery: VpsRecovery) {
        if (!recovering.compareAndSet(false, true)) return
        // Taken before the first delay and checked after each one: the user can turn the tunnel off
        // while this is waiting, and the loop's own stop() bumps the epoch too - so the check has to
        // happen before it, or a cancelled recovery spends the next 30 seconds reopening a tunnel
        // nobody asked for any more.
        val started = epoch.get()
        // UNDISPATCHED so the flag is cleared by this coroutine's own finally even when the scope is
        // cancelled before it would otherwise have started; otherwise one dead scope would latch
        // `recovering` on and refuse every later recovery for the life of the process.
        recovery.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                var attempt = 0
                while (isActive && attempt < MAX_RECONNECT_ATTEMPTS) {
                    delay(recovery.backoff.delayMillis(attempt))
                    if (epoch.get() != started) return@launch
                    // Not stop(): the lease stays held and the state never says "stopped", so the
                    // keep-alive service is not torn down and rebuilt in the middle of a handover.
                    closeHeldForward()
                    open()
                    if (_state.value is VpsTunnelState.Open) return@launch
                    attempt++
                }
                Log.w(TAG, "could not rebuild the VPS tunnel after ${MAX_RECONNECT_ATTEMPTS} attempts")
            } finally {
                recovering.set(false)
            }
        }
    }

    companion object {
        /** Ollama's port. The router's built-in `ollama` provider is pinned to 127.0.0.1:11434. */
        const val REMOTE_PORT: Int = VpsPortForwardManager.OLLAMA_PORT

        private const val TAG = "VpsTunnel"
        private const val WAKE_LOCK_TAG = "vps-tunnel"
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }
}
