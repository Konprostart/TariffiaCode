package com.konprostart.tariffiacode.core.network

/**
 * Coarse reachability of the device's default network, as far as a long-lived connection cares.
 *
 * Deliberately not "is Wi-Fi up" but "which network am I on": that is the question a tunnel needs,
 * because a phone switching from Wi-Fi to cellular keeps reporting "connected" while every socket
 * it holds is silently stranded on a network it can no longer use.
 */
sealed interface NetworkStatus {
    /** Nothing observed yet - the monitor has not been told about any network. */
    data object Unknown : NetworkStatus

    /** The default network is [networkId]. */
    data class Connected(
        val networkId: String,
    ) : NetworkStatus

    /** The default network went away. No replacement is known yet. */
    data object Disconnected : NetworkStatus
}

/**
 * Whether moving from [previous] to [current] means a connection built on the old network has to be
 * rebuilt.
 *
 * Only a *different* network qualifies. [NetworkStatus.Disconnected] alone does not: there is no
 * network to build on yet, so rebuilding would only burn a connect attempt - and the next
 * [NetworkStatus.Connected] will trigger the rebuild anyway. This keeps a flapping radio from
 * cycling the tunnel through a connect that was never going to work.
 */
fun shouldRebuildTunnel(
    previous: NetworkStatus,
    current: NetworkStatus,
): Boolean =
    current is NetworkStatus.Connected &&
        (previous !is NetworkStatus.Connected || previous.networkId != current.networkId)
