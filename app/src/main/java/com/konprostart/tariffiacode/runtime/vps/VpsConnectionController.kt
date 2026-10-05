package com.konprostart.tariffiacode.runtime.vps

import com.konprostart.tariffiacode.core.api.OpenCodeHealth
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

    suspend fun connect(): Result<OpenCodeHealth>

    suspend fun reconnect(): Result<OpenCodeHealth>

    fun disconnect()
}
