package com.konprostart.tariffiacode.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkStatusTest {
    @Test
    fun `moving to a different network rebuilds the tunnel`() {
        assertTrue(shouldRebuildTunnel(NetworkStatus.Connected("wifi"), NetworkStatus.Connected("cell")))
    }

    @Test
    fun `the first network after an unknown state rebuilds`() {
        assertTrue(shouldRebuildTunnel(NetworkStatus.Unknown, NetworkStatus.Connected("cell")))
    }

    @Test
    fun `the same network reported again does not rebuild`() {
        assertFalse(shouldRebuildTunnel(NetworkStatus.Connected("wifi"), NetworkStatus.Connected("wifi")))
    }

    @Test
    fun `losing the network does not rebuild - there is nothing to build on yet`() {
        assertFalse(shouldRebuildTunnel(NetworkStatus.Connected("wifi"), NetworkStatus.Disconnected))
    }

    @Test
    fun `recovering onto a network rebuilds even after a loss`() {
        assertTrue(shouldRebuildTunnel(NetworkStatus.Disconnected, NetworkStatus.Connected("cell")))
    }

    @Test
    fun `an unknown status never rebuilds`() {
        assertFalse(shouldRebuildTunnel(NetworkStatus.Connected("wifi"), NetworkStatus.Unknown))
    }
}
