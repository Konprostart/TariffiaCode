package com.konprostart.tariffiacode.runtime.vps

import com.konprostart.tariffiacode.core.network.NetworkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * Everything the automatic recovery needs, bundled into one optional dependency.
 *
 * A target built without it behaves exactly as before: recovery is opt-in, and tests that drive
 * connect/disconnect directly pass nothing. Bundled rather than three separate parameters because
 * the three belong together and the target already has a long parameter list.
 */
data class VpsRecovery(
    /** Long-lived scope the recovery runs in; an application scope in the app. */
    val scope: CoroutineScope,
    /** Network transitions that can mean the tunnel has to be rebuilt. See `shouldRebuildTunnel`. */
    val networkStatus: Flow<NetworkStatus>,
    val backoff: ReconnectBackoff = ReconnectBackoff(),
)
