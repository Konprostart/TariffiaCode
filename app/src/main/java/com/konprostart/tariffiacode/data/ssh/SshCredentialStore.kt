package com.konprostart.tariffiacode.data.ssh

import com.konprostart.tariffiacode.data.connection.SecureSettingsRepository

/**
 * Stores SSH secrets (passwords, private keys, passphrases) separately from [SshProfile] metadata,
 * keyed by the profile's `credentialRef`. Backed by the app's existing encrypted preferences
 * ([SecureSettingsRepository]), so it is encrypted with the same Keystore-backed MasterKey as the
 * rest of the app's credentials — no second credential-storage system.
 *
 * The load/save lambdas are injected so the logic is unit-testable without Android.
 */
class SshCredentialStore(
    private val load: () -> Map<String, String>,
    private val save: (Map<String, String>) -> Unit,
) {
    constructor(settings: SecureSettingsRepository) : this(
        load = { settings.sshCredentials() },
        save = { settings.sshCredentials = it },
    )

    /** All credential references currently holding a secret. Never returns the secrets themselves. */
    fun credentialRefs(): Set<String> =
        load()
            .filterValues { it.isNotBlank() }
            .keys
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()

    /** Secret for [credentialRef], or null when absent/blank/unparseable. */
    fun credential(credentialRef: String): SshCredential? {
        val raw = load()[credentialRef.trim()] ?: return null
        return SshCredentialCodec.decode(raw)
    }

    fun hasCredential(credentialRef: String): Boolean = credential(credentialRef)?.isUsable == true

    /** Store (or replace) the secret for [credentialRef]. */
    fun setCredential(
        credentialRef: String,
        credential: SshCredential,
    ) {
        val normalizedRef = credentialRef.trim()
        require(normalizedRef.isNotEmpty()) { "Credential reference is required" }
        require(credential.isUsable) { "Credential is empty" }
        val updated = load().toMutableMap()
        updated[normalizedRef] = SshCredentialCodec.encode(credential)
        save(updated)
    }

    /** Remove the secret for [credentialRef]. */
    fun clearCredential(credentialRef: String) {
        val normalizedRef = credentialRef.trim()
        if (normalizedRef.isEmpty()) return
        val updated = load().toMutableMap()
        if (updated.remove(normalizedRef) != null) save(updated)
    }
}
