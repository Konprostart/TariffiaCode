package com.konprostart.tariffiacode.feature.ssh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.core.ssh.SshSession
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshCredential
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.data.ssh.SshProfileStore
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
    val trustedHostKeyFingerprint: String? = null,
    val credentialAlreadyStored: Boolean = false,
    val errors: Map<String, String> = emptyMap(),
    val isNew: Boolean = true,
) {
    val portOrNull: Int? get() = port.trim().toIntOrNull()

    /** True when a brand-new profile has no credential typed and none stored to fall back on. */
    fun isCredentialMissingForNew(): Boolean {
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
        val session: SshSession,
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
 * through [SshCredentialStore], and connects through [SshConnectionManager] using the strict host-key
 * policy from the transport layer.
 */
class SshSettingsViewModel(
    private val profiles: SshProfileStore,
    private val credentials: SshCredentialStore,
    private val connections: SshConnectionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(SshSettingsUiState(profiles = profiles.profiles()))
    val state: StateFlow<SshSettingsUiState> = _state.asStateFlow()

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
                        credentialAlreadyStored = credentials.hasCredential(profile.credentialRef),
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
        val profile =
            SshProfile(
                id = form.id.ifBlank { java.util.UUID.randomUUID().toString() },
                name = form.name.trim(),
                host = form.host.trim(),
                port = port,
                username = form.username.trim(),
                authType = form.authType,
                credentialRef =
                    profiles.profile(form.id)?.credentialRef
                        ?: SshProfile.newCredentialRef(),
                trustedHostKeyFingerprint = form.trustedHostKeyFingerprint,
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
        val wasConnected = (_state.value.connection as? SshConnectionStatus.Connected)?.profileId == id
        if (wasConnected) closeConnection()
        profiles.delete(id)
        _state.update { it.copy(profiles = profiles.profiles()) }
    }

    // ---- connect ----

    fun connect(id: String) {
        val profile = profiles.profile(id) ?: return
        if (_state.value.connection is SshConnectionStatus.Connecting) return
        _state.update {
            it.copy(
                connection = SshConnectionStatus.Connecting(id),
                pendingHostKey = null,
                message = null,
            )
        }
        viewModelScope.launch {
            // A transport that throws (including an Error from native/static init) must never escape
            // into the coroutine and crash the app; surface it as a normal failure instead.
            val outcome =
                runCatching { connections.connect(profile) }
                    .getOrElse { error ->
                        SshConnectionOutcome.Failed(
                            "${error::class.java.simpleName}: ${error.message ?: "SSH connection failed"}",
                        )
                    }
            when (outcome) {
                is SshConnectionOutcome.Connected -> {
                    _state.update {
                        it.copy(
                            connection = SshConnectionStatus.Connected(id, outcome.session),
                        )
                    }
                }
                is SshConnectionOutcome.NeedsHostKeyTrust -> {
                    _state.update {
                        it.copy(
                            connection = SshConnectionStatus.Idle,
                            pendingHostKey = PendingHostKey(id, outcome.hostKey, mismatch = false),
                        )
                    }
                }
                is SshConnectionOutcome.HostKeyMismatch -> {
                    _state.update {
                        it.copy(
                            connection =
                                SshConnectionStatus.Error(
                                    id,
                                    "Host key mismatch: expected ${outcome.expectedFingerprint}, got ${outcome.presentedFingerprint}",
                                ),
                            pendingHostKey = PendingHostKey(id, SshHostKey("", outcome.presentedFingerprint), mismatch = true),
                        )
                    }
                }
                is SshConnectionOutcome.MissingCredential -> {
                    _state.update {
                        it.copy(connection = SshConnectionStatus.Error(id, "No stored credential for this profile"))
                    }
                }
                is SshConnectionOutcome.Failed -> {
                    _state.update {
                        it.copy(connection = SshConnectionStatus.Error(id, outcome.message))
                    }
                }
            }
        }
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
        profiles.upsert(connections.trustHostKey(profile, pending.hostKey))
        _state.update {
            it.copy(
                profiles = profiles.profiles(),
                pendingHostKey = null,
            )
        }
        connect(pending.profileId)
    }

    fun dismissPendingHostKey() {
        _state.update {
            it.copy(
                pendingHostKey = null,
                connection = SshConnectionStatus.Idle,
            )
        }
    }

    fun closeConnection() {
        (_state.value.connection as? SshConnectionStatus.Connected)?.session?.close()
        _state.update { it.copy(connection = SshConnectionStatus.Idle) }
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
    }
}
