package com.konprostart.tariffiacode.runtime.ssh

import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.core.ssh.SshSession
import com.konprostart.tariffiacode.data.ssh.SshHeldSessionStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.data.ssh.SshProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the app-scoped holder is currently holding.
 *
 * Same shape the settings screen already renders, minus the session itself: the session belongs to
 * this owner, never to a screen, which is what made it die (and leak) every time the screen did.
 */
sealed interface HeldSshConnection {
    data object Idle : HeldSshConnection

    data class Connecting(
        val profileId: String,
    ) : HeldSshConnection

    data class Connected(
        val profileId: String,
    ) : HeldSshConnection

    data class Failed(
        val profileId: String,
        val message: String,
    ) : HeldSshConnection

    /** The server presented a key the user has not accepted yet; the screen shows the dialog. */
    data class NeedsTrust(
        val profileId: String,
        val hostKey: SshHostKey,
        val mismatch: Boolean,
    ) : HeldSshConnection
}

/**
 * The app's one long-lived SSH shell session, held outside any screen.
 *
 * The settings screen used to own the session it opened with Connect: the state lived in a
 * back-stack-scoped ViewModel, so walking away made the screen say "Connect" again, and the session
 * behind that label was never closed by anyone - one leaked SSH client per press. This holder is
 * process-scoped instead: a session survives leaving the screen and every later screen, until the
 * user explicitly presses Disconnect, or asks for another profile, or the process dies.
 *
 * Reconnection policy: [restoreIfRemembered] is called on cold start and on every foreground
 * return, and re-opens the profile the user last connected. Nothing here re-opens without those
 * events: an SSH session is not something to poke at behind the user's back.
 */
class SshSessionHolder(
    private val opener: suspend (SshProfile) -> SshConnectionOutcome,
    private val profiles: SshProfileStore,
    private val selection: SshHeldSessionStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<HeldSshConnection>(HeldSshConnection.Idle)
    val state: StateFlow<HeldSshConnection> = _state.asStateFlow()

    /** The profile whose session is currently held, or null when nothing is held. */
    val heldProfileId: String?
        get() = (_state.value as? HeldSshConnection.Connected)?.profileId

    private val epoch = AtomicInteger()
    private var session: SshSession? = null
    private var upkeeps: List<Job> = emptyList()

    /** Opens a session for [profileId], reporting what came back through [state]. */
    fun connect(profileId: String) {
        // The attempt slot is reserved synchronously, not when the launch gets to run: a Disconnect
        // tapped before that launch even starts would otherwise be undone by the very attempt the
        // user just cancelled, once the queue finally reaches it.
        val request = epoch.incrementAndGet()
        scope.launch { openFor(profileId, request) }
    }

    /** Closes the held session; the one caller-instigated path that disconnects. */
    fun disconnect() {
        epoch.incrementAndGet()
        closeHeld()
        selection.setProfileId(null)
        _state.value = HeldSshConnection.Idle
    }

    /** Backs out of an unanswered host-key prompt; nothing was opened, so nothing to close. */
    fun dismissTrust() {
        if (_state.value is HeldSshConnection.NeedsTrust) _state.value = HeldSshConnection.Idle
    }

    /**
     * Re-opens the profile the user last connected, once the connection is actually down.
     *
     * Called on cold start and on entering the foreground: a network change while the device slept
     * kills a socket with nobody looking, and the earliest legitimate moment to rebuild is when the
     * app is visible again. A live or in-flight session is left exactly as it is; a failed opening
     * is what gets retried, since [HeldSshConnection.Failed] means there is nothing being held.
     */
    fun restoreIfRemembered() {
        if (_state.value is HeldSshConnection.Connecting || _state.value is HeldSshConnection.Connected) return
        val profileId = selection.profileId() ?: return
        connect(profileId)
    }

    /** Closes the held session when it quietly died, and reports it instead of claiming it up. */
    private fun pruneIfDead() {
        val held = session
        if (held != null && !held.isOpen) {
            epoch.incrementAndGet()
            closeHeld()
            _state.value = HeldSshConnection.Idle
        }
    }

    private suspend fun openFor(
        profileId: String,
        request: Int,
    ) {
        val started = request
        // A disconnect, a prune, or a newer request took over between the tap and this attempt
        // starting: this request is stale before it has done anything.
        if (epoch.get() != started) return
        val profile =
            profiles.profile(profileId) ?: run {
                _state.value = HeldSshConnection.Idle
                return
            }
        // Second press on the same profile while its handshake is running is a no-op, the way the
        // screen's old ViewModel ignored it; a different profile supersedes via a newer request.
        val inFlight = _state.value as? HeldSshConnection.Connecting
        if (inFlight?.profileId == profileId) return
        pruneIfDead()
        _state.value = HeldSshConnection.Connecting(profileId)
        val outcome = openSessionSafely(profile)
        if (epoch.get() != started) {
            // A disconnect, a prune, or a takeover landed inside the SSH handshake: close what this
            // attempt built and let the newer actor decide the state.
            runCatching { (outcome as? SshConnectionOutcome.Connected)?.session?.close() }
            return
        }
        when (outcome) {
            is SshConnectionOutcome.Connected -> {
                val fresh = outcome.session
                closeHeld()
                session = fresh
                if (epoch.get() != started) {
                    closeHeld()
                    return
                }
                // A browsed-by-nobody shell still fills its pipe: MINA writes stdout into a bounded
                // buffer, and a stalled read can wedge the PTY. Draining and dropping the bytes
                // keeps the channel flowing; nothing else consumes them today.
                upkeeps = listOf(scope.launch { runCatching { fresh.output.collect {} } })
                selection.setProfileId(profileId)
                _state.value = HeldSshConnection.Connected(profileId)
            }
            is SshConnectionOutcome.NeedsHostKeyTrust ->
                _state.value = HeldSshConnection.NeedsTrust(profileId, outcome.hostKey, mismatch = false)
            is SshConnectionOutcome.HostKeyMismatch ->
                _state.value =
                    HeldSshConnection.NeedsTrust(
                        profileId,
                        SshHostKey("", outcome.presentedFingerprint),
                        mismatch = true,
                    )
            is SshConnectionOutcome.MissingCredential ->
                _state.value = HeldSshConnection.Failed(profileId, "No stored credential for this profile")
            is SshConnectionOutcome.Failed -> _state.value = HeldSshConnection.Failed(profileId, outcome.message)
        }
    }

    /**
     * A transport that throws, including a static-init Error from MINA, surfaces as a failure rather
     * than escaping into the holder's scope and killing the process - the same deal the settings
     * ViewModel made when it owned this path.
     */
    private suspend fun openSessionSafely(profile: SshProfile): SshConnectionOutcome =
        runCatching { opener(profile) }
            .getOrElse { error ->
                SshConnectionOutcome.Failed(
                    "SSH connection failed: ${error::class.java.simpleName}: ${error.message ?: "no details"}",
                )
            }

    private fun closeHeld() {
        val held = session
        session = null
        held?.let { runCatching { it.close() } }
        val jobs = upkeeps
        upkeeps = emptyList()
        jobs.forEach { runCatching { it.cancel() } }
    }
}
