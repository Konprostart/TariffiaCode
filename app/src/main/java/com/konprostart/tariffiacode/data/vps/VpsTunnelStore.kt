package com.konprostart.tariffiacode.data.vps

import com.konprostart.tariffiacode.data.connection.SecureSettingsRepository

/**
 * Remembers whether the user wants the VPS port forward held open in the background.
 *
 * The forward itself is held by
 * [com.konprostart.tariffiacode.runtime.vps.VpsTunnelController], which lives as long as the app
 * process; this only remembers the *wish*, so a cold start can put it back up without asking again.
 *
 * Off by default, and staying that way until the user says otherwise: an SSH session to their own
 * server is not something to open behind their back, and an open tunnel keeps a socket and a wake
 * lock alive for as long as it lasts.
 *
 * The load/save lambdas are injected so the logic is unit-testable without Android.
 */
class VpsTunnelStore(
    private val loadEnabled: () -> Boolean,
    private val saveEnabled: (Boolean) -> Unit,
) {
    constructor(settings: SecureSettingsRepository) : this(
        loadEnabled = { settings.vpsTunnelEnabled },
        saveEnabled = { settings.vpsTunnelEnabled = it },
    )

    fun isEnabled(): Boolean = loadEnabled()

    fun setEnabled(enabled: Boolean) = saveEnabled(enabled)
}
