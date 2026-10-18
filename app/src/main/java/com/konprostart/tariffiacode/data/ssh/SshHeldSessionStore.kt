package com.konprostart.tariffiacode.data.ssh

import com.konprostart.tariffiacode.data.connection.SecureSettingsRepository

/**
 * Remembers which SSH profile the user asked to stay connected with the settings screen's Connect
 * button.
 *
 * The session itself is held by
 * [com.konprostart.tariffiacode.runtime.ssh.SshSessionHolder], which lives as long as the app
 * process; this only remembers the *wish*, so a cold start or a foreground return can put the
 * session back up without asking again.
 *
 * Written when a connection succeeds and cleared the moment the user presses Disconnect, so the
 * only ever re-opened sessions are the ones the user explicitly asked for.
 */
class SshHeldSessionStore(
    private val loadProfileId: () -> String?,
    private val saveProfileId: (String?) -> Unit,
) {
    constructor(settings: SecureSettingsRepository) : this(
        loadProfileId = { settings.sshHeldProfileId },
        saveProfileId = { settings.sshHeldProfileId = it },
    )

    fun profileId(): String? = loadProfileId()

    fun setProfileId(id: String?) = saveProfileId(id)
}
