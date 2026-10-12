package com.konprostart.tariffiacode.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches which network the device is on, so a long-lived connection can tell a handover from a
 * healthy session.
 *
 * A phone moving between Wi-Fi and cellular keeps reporting a perfectly good connection while every
 * socket it holds is stranded on a network it can no longer reach - the radio does not hang up, so
 * neither side notices. Nothing in the app watched for that, which is why a VPS session could look
 * connected and then do nothing for the length of a socket timeout.
 *
 * Registered for the life of the process: the callback dies with it, so there is nothing to
 * unregister on a normal exit.
 */
class NetworkMonitor(context: Context) {
    private val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val mutableStatus = MutableStateFlow<NetworkStatus>(NetworkStatus.Unknown)

    /** Latest observed network; starts as [NetworkStatus.Unknown] until [start] reports one. */
    val status: StateFlow<NetworkStatus> = mutableStatus.asStateFlow()

    /** Not synchronised: there is one caller (the application), and it calls this once at startup. */
    private var started = false

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mutableStatus.value = NetworkStatus.Connected(network.networkHandle.toString())
            }

            override fun onLost(network: Network) {
                // Only the network we are actually reporting being lost clears the status. During a
                // handover Android may deliver onAvailable(new) before onLost(old), and treating
                // that late onLost as current would overwrite the new network with "disconnected"
                // and hide the very handover this exists to catch.
                if (mutableStatus.value == NetworkStatus.Connected(network.networkHandle.toString())) {
                    mutableStatus.value = NetworkStatus.Disconnected
                }
            }
        }

    /** Begin observing. Safe to call more than once; only the first call registers. */
    fun start() {
        if (started) return
        val manager = connectivityManager ?: return
        started = true
        // registerDefaultNetworkCallback needs ACCESS_NETWORK_STATE, which the app already declares.
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onFailure {
                // Without the permission on some OEM builds the app simply keeps the current
                // status; a missing signal must not take the rest of the connection down.
                started = false
            }
    }
}
