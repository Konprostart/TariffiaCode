package com.konprostart.tariffiacode.runtime.vps

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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
 *  - the router being started, when the user asked for it to be kept open
 *    ([onRouterStarted]);
 *  - the app coming back to the foreground after process death ([onForeground]), because the process
 *    is the thing that holds the forward and process death is the one way it silently goes away.
 *
 * Recovery is optional, as it is for [VpsRuntimeTarget]: a built without [recovery] behaves exactly
 * as before. With it, a Wi-Fi <-> cellular handover rebuilds a tunnel that was up, since that is
 * precisely when the socket behind it is dead and nothing else would notice.
 *
 * The opener is a suspend lambda rather than [VpsPortForwardManager] so the whole lifecycle can be
 * driven in a unit test without an SSH server; the app passes a lambda that calls the manager.
 */
class VpsTunnelController(
    private val opener: suspend (SshProfile) -> VpsPortForwardOutcome,
    private val profileProvider: () -> SshProfile?,
    private val store: VpsTunnelStore,
    private val scope: CoroutineScope,
    private val profiles: SshProfileStore? = null,
    private val work: RuntimeWorkTracker? = null,
    private val recovery: VpsRecovery? = null,
) {
    private val _state = MutableStateFlow<VpsTunnelState>(VpsTunnelState.Stopped)
    val state: StateFlow<VpsTunnelState> = _state.asStateFlow()

    private val _pendingHostKey = MutableStateFlow<SshHostKey?>(null)
    val pendingHostKey: StateFlow<SshHostKey?> = _pendingHostKey.asStateFlow()

    /** The lease is held for as long as the forward is, so the device does not sleep mid-session. */
    @Volatile
    private var lease: RuntimeWorkTracker.Lease? = null

    @Volatile
    private var forward: SshPortForward? = null

    /** One open at a time: a second start while the first is in flight must not leak a forward. */
    private val opening = AtomicBoolean(false)

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
        val held = forward
        forward = null
        if (held != null) runCatching { held.close() }
        releaseLease()
        _state.value = VpsTunnelState.Stopped
    }

    /** Called when the router is started: what the user asked for should be up before an agent dials in. */
    fun onRouterStarted() {
        if (store.isEnabled()) start()
    }

    /** Called when the app comes back to the foreground; process death is how a held forward is lost. */
    fun onForeground() {
        if (store.isEnabled()) start()
    }

    /** Persist the key the user confirmed, then try again with it. Returns the profile that was updated. */
    fun trustPendingHostKey(): SshProfile? {
        val pending = _pendingHostKey.value ?: return null
        val profile = profileProvider() ?: return null
        val store = profiles ?: return null
        val trusted = profile.trusting(pending.sha256Fingerprint)
        store.upsert(trusted)
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
        if (forward != null || _state.value is VpsTunnelState.Opening) return
        if (!opening.compareAndSet(false, true)) return
        try {
            _state.value = VpsTunnelState.Opening
            val profile = trustedProfile ?: profileProvider()
            if (profile == null) {
                _state.value = VpsTunnelState.Failed(NO_PROFILE)
                return
            }
            when (val outcome = opener(profile)) {
                is VpsPortForwardOutcome.Connected -> {
                    forward = outcome.forward
                    // Taken only once a forward is actually held: a lease for an attempt that failed
                    // would keep the device awake for a tunnel that does not exist.
                    lease = work?.acquire(WAKE_LOCK_TAG)
                    _state.value = VpsTunnelState.Open(outcome.localUrl)
                }

                is VpsPortForwardOutcome.NeedsHostKeyTrust -> {
                    _pendingHostKey.value = outcome.hostKey
                    _state.value = VpsTunnelState.Stopped
                }

                VpsPortForwardOutcome.MissingCredential -> _state.value = VpsTunnelState.Failed(NO_CREDENTIAL)
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
     * The first emission is a baseline, never a trigger: the monitor delivers the current network on
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
                // Gated on the forward rather than on the connection being healthy: a handover is
                // exactly the case where it is not, so waiting for it to look healthy is how this
                // goes unrecovered.
                if (handover && forward != null) {
                    stop()
                    start()
                }
            }
        }
    }

    companion object {
        /** Ollama's port. The router's built-in `ollama` provider is pinned to 127.0.0.1:11434. */
        const val REMOTE_PORT: Int = VpsPortForwardManager.OLLAMA_PORT

        private const val WAKE_LOCK_TAG = "vps-tunnel"
        private const val NO_PROFILE = "Select an SSH connection for the VPS first"
        private const val NO_CREDENTIAL = "No stored SSH credential for that connection"
    }
}
