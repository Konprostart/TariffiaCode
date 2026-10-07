package com.konprostart.tariffiacode.runtime.local

/**
 * Remembers the wireless-debugging port the user last connected to, so the ADB link can be
 * restored automatically after the app or the Linux runtime restarts. The pairing keys already
 * survive restarts inside the rootfs (`/root/.android` is carried over on reinstall), so only the
 * port has to be persisted — a saved port can be re-connected without pairing again.
 */
interface AdbConnectionStore {
    fun saveConnectedPort(port: Int)

    fun loadConnectedPort(): Int?

    fun clearConnectedPort()

    /**
     * Whether the user has explicitly allowed the app to keep a wireless-ADB link to this device for
     * agents to use. Defaults to false: an agent must not be able to control the device over adb
     * unless the user opted in. The manual pair/connect workflow is unaffected — it is driven by an
     * explicit user action — but the unattended auto-reconnect that keeps the link alive for agents is
     * gated on this.
     */
    var agentAdbEnabled: Boolean
}

/** Volatile in-memory store used as a safe default and by unit tests. */
class InMemoryAdbConnectionStore : AdbConnectionStore {
    @Volatile
    private var port: Int? = null

    @Volatile
    override var agentAdbEnabled: Boolean = false

    override fun saveConnectedPort(port: Int) {
        this.port = port
    }

    override fun loadConnectedPort(): Int? = port

    override fun clearConnectedPort() {
        port = null
    }
}
