package com.konprostart.tariffiacode.data.ssh

import com.konprostart.tariffiacode.data.connection.SecureSettingsRepository

/**
 * CRUD over the saved [SshProfile] list, backed by the app's encrypted preferences. Deleting a
 * profile also removes its secret from [SshCredentialStore] so no orphaned credential is left behind.
 */
class SshProfileStore(
    private val load: () -> List<SshProfile>,
    private val save: (List<SshProfile>) -> Unit,
    private val credentials: SshCredentialStore,
) {
    constructor(settings: SecureSettingsRepository) : this(
        load = { settings.sshProfiles },
        save = { settings.sshProfiles = it },
        credentials = SshCredentialStore(settings),
    )

    fun profiles(): List<SshProfile> = load()

    fun profile(id: String): SshProfile? = profiles().firstOrNull { it.id == id }

    /** Insert or replace [profile], preserving list order for existing ids. */
    fun upsert(profile: SshProfile) {
        val current = profiles()
        val index = current.indexOfFirst { it.id == profile.id }
        val updated =
            if (index >= 0) {
                current.toMutableList().also { it[index] = profile }
            } else {
                current + profile
            }
        save(updated)
    }

    /** Remove the profile and its stored secret. */
    fun delete(id: String) {
        val target = profile(id) ?: return
        save(profiles().filterNot { it.id == id })
        credentials.clearCredential(target.credentialRef)
    }
}
