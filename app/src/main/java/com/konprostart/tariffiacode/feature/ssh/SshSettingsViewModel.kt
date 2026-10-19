package com.konprostart.tariffiacode.feature.ssh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshCredential
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.data.ssh.SshProfileStore
import com.konprostart.tariffiacode.runtime.ssh.HeldSshConnection
import com.konprostart.tariffiacode.runtime.ssh.SshSessionHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Editor state for the SSH profile dialog. The credential fields are transient: they are written to
 * the encrypted [SshCredentialStore] on save and never held in the profile list.
 */
data class SshProfileForm(
    val id: String = "",
    val name: String = "",
    val host: String = "",
    val port: String = SshProfile.DEFAULT_PORT.toString(),
    val username: String = "",
    val authType: SshAuthType = SshAuthType.PASSWORD,
    val password: String = "",
    val privateKeyPem: String = "",
    val passphrase: String = "",
    val opencodePassword: String = "",
    val trustedHostKeyFingerprint: String? = null,
    val storedCredentialAuthType: SshAuthType? = null,
    /** True when this profile already has a saved OpenCode password, so the field can stay blank. */
    val storedOpencodePassword: Boolean = false,
    val errors: Map<String, String> = emptyMap(),
    val isNew: Boolean = true,
) {
    val portOrNull: Int? get() = port.trim().toIntOrNull()
    val credentialAlreadyStored: Boolean get() = storedCredentialAuthType == authType

    /**
     * Whether a usable OpenCode password is available for this profile.
     *
     * Either the user typed one, or one is already stored. Blank means neither, which is what makes
     * the VPS connection fail its auth check - so the editor has to be able to tell that apart from
     * "the user is editing and does not want to retype the secret".
     */
    fun hasOpencodePassword(): Boolean = opencodePassword.isNotBlank() || storedOpencodePassword

    /** True when the current auth type has neither newly entered nor matching stored credentials. */
    fun isCredentialMissing(): Boolean {
        if (credentialAlreadyStored) return false
        return when (authType) {
            SshAuthType.PASSWORD -> password.isBlank()
            SshAuthType.PRIVATE_KEY -> privateKeyPem.isBlank()
        }
    }
}

/** One connected (or connecting) SSH session state for the screen. */
sealed interface SshConnectionStatus {
    data object Idle : SshConnectionStatus

    data class Connecting(
        val profileId: String,
    ) : SshConnectionStatus

    data class Connected(
        val profileId: String,
    ) : SshConnectionStatus

    data class Error(
        val profileId: String,
        val message: String,
    ) : SshConnectionStatus
}

data class SshSettingsUiState(
    val profiles: List<SshProfile> = emptyList(),
    val form: SshProfileForm? = null,
    val connection: SshConnectionStatus = SshConnectionStatus.Idle,
    /** Set when the server presented a key that needs explicit confirmation before trusting. */
    val pendingHostKey: PendingHostKey? = null,
    val message: String? = null,
)

/** A host key awaiting the user's explicit trust decision. */
data class PendingHostKey(
    val profileId: String,
    val hostKey: SshHostKey,
    val mismatch: Boolean,
)

/**
 * Backs the SSH settings screen: lists saved profiles, edits one in [SshProfileForm], stores secrets
 * through [SshCredentialStore], and delegates connecting to the app-scoped [SshSessionHolder].
 */
class SshSettingsViewModel(
    private val profiles: SshProfileStore,
    private val credentials: SshCredentialStore,
    private val sessions: SshSessionHolder,
    /**
     * Called with the profile a Connect press is for.
     *
     * This screen is where a saved connection is chosen, but the VPS runtime and its tunnel take
     * their profile from the Remote Projects screen only - so without this, a Connected screen here
     * and "Select an SSH connection for the VPS first" there stay two disconnected truths.
     */
    private val onSelectForVps: (SshProfile) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(SshSettingsUiState(profiles = profiles.profiles()))
    val state: StateFlow<SshSettingsUiState> = _state.asStateFlow()

    init {
        // The holder outlives this screen, so its state is the truth behind every "Connected"
        // label the screen ever shows; walking away and back restores it from here untouched.
        viewModelScope.launch {
            sessions.state.collect { held ->
                _state.update { current ->
                    when (held) {
                        is HeldSshConnection.Idle ->
                            current.copy(connection = SshConnectionStatus.Idle, pendingHostKey = null)
                        is HeldSshConnection.Connecting ->
                            current.copy(connection = SshConnectionStatus.Connecting(held.profileId), pendingHostKey = null)
                        is HeldSshConnection.Connected ->
                            current.copy(connection = SshConnectionStatus.Connected(held.profileId), pendingHostKey = null)
                        is HeldSshConnection.Failed ->
                            current.copy(connection = SshConnectionStatus.Error(held.profileId, held.message), pendingHostKey = null)
                        is HeldSshConnection.NeedsTrust ->
                            current.copy(
                                connection = SshConnectionStatus.Idle,
                                pendingHostKey = PendingHostKey(held.profileId, held.hostKey, held.mismatch),
                            )
                    }
                }
            }
        }
    }

    fun refresh() {
        _state.update { it.copy(profiles = profiles.profiles()) }
    }

    // ---- editor ----

    fun newProfile() {
        _state.update {
            it.copy(
                form = SshProfileForm(),
                message = null,
            )
        }
    }

    fun editProfile(id: String) {
        val profile = profiles.profile(id) ?: return
        _state.update {
            it.copy(
                form =
                    SshProfileForm(
                        id = profile.id,
                        name = profile.name,
                        host = profile.host,
                        port = profile.port.toString(),
                        username = profile.username,
                        authType = profile.authType,
                        trustedHostKeyFingerprint = profile.trustedHostKeyFingerprint,
                        storedCredentialAuthType =
                            credentials.credential(profile.credentialRef)
                                ?.takeIf { it.isUsable }
                                ?.let(::authTypeOf),
                        // Reported, never pre-filled: the field stays blank so an existing secret is
                        // not shown on screen, and a blank field means "keep what is stored".
                        storedOpencodePassword = !profile.opencodePassword.isNullOrBlank(),
                        isNew = false,
                    ),
                message = null,
            )
        }
    }

    fun dismissEditor() {
        _state.update { it.copy(form = null) }
    }

    fun updateForm(transform: (SshProfileForm) -> SshProfileForm) {
        _state.update { current -> current.form?.let { current.copy(form = transform(it)) } ?: current }
    }

    /**
     * Validate and persist the form. The secret is stored only when the user actually typed one, so
     * editing a profile without touching the credential keeps the existing secret.
     */
    fun saveProfile() {
        val form = _state.value.form ?: return
        val port = form.portOrNull ?: SshProfile.DEFAULT_PORT
        val existing = profiles.profile(form.id)
        val profile =
            SshProfile(
                id = form.id.ifBlank { java.util.UUID.randomUUID().toString() },
                name = form.name.trim(),
                host = form.host.trim(),
                port = port,
                username = form.username.trim(),
                authType = form.authType,
                credentialRef = existing?.credentialRef ?: SshProfile.newCredentialRef(),
                trustedHostKeyFingerprint = form.trustedHostKeyFingerprint,
                // A typed value replaces the stored one; a blank field keeps it. Losing it here
                // would break every existing VPS connection the first time the user renames it,
                // and the editor is the only place a stored secret can be replaced.
                opencodePassword =
                    form.opencodePassword
                        .takeIf { it.isNotBlank() }
                        ?: existing?.opencodePassword,
            )
        val errors = SshProfileValidator.validate(profile)
        if (errors.isNotEmpty()) {
            updateForm { it.copy(errors = errors) }
            return
        }
        val credential = credentialFromForm(form) ?: existingCredentialIfEditing(form)
        if (credential == null) {
            updateForm { it.copy(errors = mapOf(SshProfileValidator.FIELD_CREDENTIAL to "missing")) }
            return
        }
        credentials.setCredential(profile.credentialRef, credential)
        profiles.upsert(profile)
        _state.update {
            it.copy(
                profiles = profiles.profiles(),
                form = null,
                message = null,
            )
        }
    }

    fun deleteProfile(id: String) {
        if (sessions.heldProfileId == id) closeConnection()
        profiles.delete(id)
        _state.update { it.copy(profiles = profiles.profiles()) }
    }

    // ---- connect ----

    fun connect(id: String) {
        // A new attempt starts from a clean banner: the previous failure text belongs to the
        // previous attempt, not to whatever this one reports.
        _state.update { it.copy(message = null) }
        // Selecting before the handshake, not after it: pressing Connect is the user's statement
        // of which connection they mean, and the VPS runtime/tunnel can start using it while this
        // screen is still connecting. An unknown id selects nothing - the holder reports it.
        profiles.profile(id)?.let(onSelectForVps)
        sessions.connect(id)
    }

    /** User confirmed the presented host key; persist it and retry the connection. */
    fun trustPendingHostKey() {
        val pending = _state.value.pendingHostKey ?: return
        if (pending.mismatch) {
            // A changed key is never silently trusted from this dialog.
            dismissPendingHostKey()
            return
        }
        val profile = profiles.profile(pending.profileId) ?: return
        profiles.upsert(profile.trusting(pending.hostKey.sha256Fingerprint))
        _state.update {
            it.copy(
                profiles = profiles.profiles(),
                pendingHostKey = null,
            )
        }
        connect(pending.profileId)
    }

    fun dismissPendingHostKey() {
        sessions.dismissTrust()
    }

    fun closeConnection() {
        sessions.disconnect()
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun credentialFromForm(form: SshProfileForm): SshCredential? =
        when (form.authType) {
            SshAuthType.PASSWORD -> form.password.takeIf { it.isNotBlank() }?.let { SshCredential.Password(it) }
            SshAuthType.PRIVATE_KEY ->
                form.privateKeyPem.takeIf { it.isNotBlank() }?.let {
                    SshCredential.PrivateKey(it, form.passphrase.takeIf { p -> p.isNotBlank() })
                }
        }

    private fun existingCredentialIfEditing(form: SshProfileForm): SshCredential? {
        val existing = profiles.profile(form.id) ?: return null
        return credentials.credential(existing.credentialRef)
            ?.takeIf { it.isUsable && authTypeOf(it) == form.authType }
    }

    private fun authTypeOf(credential: SshCredential): SshAuthType =
        when (credential) {
            is SshCredential.Password -> SshAuthType.PASSWORD
            is SshCredential.PrivateKey -> SshAuthType.PRIVATE_KEY
        }
}
