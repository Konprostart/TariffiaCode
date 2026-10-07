package com.konprostart.tariffiacode.runtime.vps

import com.konprostart.tariffiacode.core.api.OpenCodeHealth
import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.data.remote.RemoteProject
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.runtime.RuntimeState
import kotlinx.coroutines.flow.StateFlow

/**
 * The minimal connection lifecycle the VPS UI needs, so the screen can drive connect/disconnect without
 * depending on the concrete runtime class. Implemented by [VpsRuntimeTarget].
 *
 * The contract is deliberately small:
 * - [connect] opens the SSH forward and health-checks the VPS OpenCode; a failure leaves a clean,
 *   non-connected state (no half-open forward);
 * - [disconnect] closes the SSH forward/session and is safe to call repeatedly;
 * - [reconnect] is a fresh disconnect + connect.
 */
interface VpsConnectionController {
    val state: StateFlow<RuntimeState>

    /** True while the SSH forward backing the runtime is open. */
    val isForwardOpen: Boolean

    /**
     * A server key awaiting the user's explicit trust, set when a connect was refused because the
     * profile has no trusted fingerprint yet. Null on a key mismatch: a changed key is never offered
     * for trust, it is refused outright.
     */
    val pendingHostKey: StateFlow<SshHostKey?>

    /**
     * Record [hostKey] as trusted for the selected profile, clearing [pendingHostKey]. Returns the
     * updated profile so the caller can persist it, or null when no profile is selected.
     */
    fun trustHostKey(hostKey: SshHostKey): SshProfile?

    /** Drop [pendingHostKey] without trusting it (the user declined). */
    fun dismissHostKey()

    /** The Remote Project mapping currently applied to this runtime, if any. */
    val selectedRemoteProject: StateFlow<RemoteProject?>

    /** Apply (or clear, with null) the Remote Project mapping whose remote path this runtime uses. */
    fun selectRemoteProject(project: RemoteProject?)

    suspend fun connect(): Result<OpenCodeHealth>

    suspend fun reconnect(): Result<OpenCodeHealth>

    fun disconnect()
}
