package com.konprostart.tariffiacode.feature.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.data.remote.RemoteProject
import com.konprostart.tariffiacode.data.remote.RemoteProjectStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.data.ssh.SshProfileStore
import com.konprostart.tariffiacode.runtime.RuntimeState
import com.konprostart.tariffiacode.runtime.vps.VpsConnectionController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Editor state for a Remote Project mapping. No secret is ever held here. */
data class RemoteProjectForm(
    val id: String = "",
    val displayName: String = "",
    val projectRef: String = "",
    val sshProfileId: String = "",
    val remotePath: String = "",
    val errors: Map<String, String> = emptyMap(),
    val isNew: Boolean = true,
)

data class RemoteProjectUiState(
    val projects: List<RemoteProject> = emptyList(),
    val sshProfiles: List<SshProfile> = emptyList(),
    /** Ids of mappings whose SSH profile no longer exists. */
    val danglingIds: Set<String> = emptySet(),
    val appliedId: String? = null,
    val connectionState: RuntimeState = RuntimeState.Disconnected,
    /**
     * A server key the VPS connection is waiting on: the profile has no trusted fingerprint yet, so
     * the user must confirm the fingerprint before the connection proceeds. Null on a key mismatch,
     * which is refused outright and never offered for trust.
     */
    val pendingHostKey: SshHostKey? = null,
    val form: RemoteProjectForm? = null,
    val message: String? = null,
)

/**
 * Backs the Remote Project screen: lists mappings, edits one, applies a mapping to the VPS runtime, and
 * drives the VPS connection lifecycle through [controller].
 */
class RemoteProjectViewModel(
    private val store: RemoteProjectStore,
    private val sshProfiles: SshProfileStore,
    private val controller: VpsConnectionController,
    private val onApply: (RemoteProject, SshProfile) -> Unit,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            loadState().copy(connectionState = controller.state.value),
        )
    val state: StateFlow<RemoteProjectUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            controller.state.collect { runtimeState ->
                _state.update { it.copy(connectionState = runtimeState) }
            }
        }
        viewModelScope.launch {
            controller.pendingHostKey.collect { hostKey ->
                _state.update { it.copy(pendingHostKey = hostKey) }
            }
        }
        viewModelScope.launch {
            controller.selectedRemoteProject.collect { project ->
                _state.update { it.copy(appliedId = project?.id) }
            }
        }
    }

    private fun loadState(): RemoteProjectUiState {
        val projects = store.projects()
        return RemoteProjectUiState(
            projects = projects,
            sshProfiles = sshProfiles.profiles(),
            danglingIds = store.danglingProjects().map { it.id }.toSet(),
            // Restored from the persisted selection, so the applied mapping is still marked after a restart.
            appliedId = controller.selectedRemoteProject.value?.id,
        )
    }

    fun refresh() {
        val projects = store.projects()
        _state.update {
            it.copy(
                projects = projects,
                sshProfiles = sshProfiles.profiles(),
                danglingIds = store.danglingProjects().map { project -> project.id }.toSet(),
            )
        }
    }

    fun newProject() {
        _state.update {
            it.copy(
                form =
                    RemoteProjectForm(
                        sshProfileId = it.sshProfiles.firstOrNull()?.id.orEmpty(),
                    ),
                message = null,
            )
        }
    }

    fun editProject(id: String) {
        val project = store.project(id) ?: return
        _state.update {
            it.copy(
                form =
                    RemoteProjectForm(
                        id = project.id,
                        displayName = project.displayName,
                        projectRef = project.projectRef,
                        sshProfileId = project.sshProfileId,
                        remotePath = project.remotePath,
                        isNew = false,
                    ),
                message = null,
            )
        }
    }

    fun dismissEditor() {
        _state.update { it.copy(form = null) }
    }

    fun updateForm(transform: (RemoteProjectForm) -> RemoteProjectForm) {
        _state.update { current -> current.form?.let { current.copy(form = transform(it)) } ?: current }
    }

    fun saveProject() {
        val form = _state.value.form ?: return
        val project =
            RemoteProject(
                id = form.id.ifBlank { java.util.UUID.randomUUID().toString() },
                displayName = form.displayName.trim(),
                projectRef = form.projectRef.trim(),
                sshProfileId = form.sshProfileId.trim(),
                remotePath = form.remotePath.trim(),
            )
        val errors = store.upsert(project)
        if (errors.isNotEmpty()) {
            updateForm { it.copy(errors = errors) }
            return
        }
        _state.update { it.copy(form = null, message = null) }
        refresh()
    }

    fun deleteProject(id: String) {
        store.delete(id)
        if (_state.value.appliedId == id) {
            // Stop using the deleted mapping and clear the persisted selection, not just the marker.
            controller.selectRemoteProject(null)
        }
        _state.update { if (it.appliedId == id) it.copy(appliedId = null) else it }
        refresh()
    }

    /** Resolve and apply the mapping to the VPS runtime. */
    fun apply(id: String) {
        val project = store.project(id) ?: return
        val profile = sshProfiles.profile(project.sshProfileId)
        if (profile == null) {
            _state.update { it.copy(message = "The SSH connection for this mapping no longer exists") }
            refresh()
            return
        }
        onApply(project, profile)
        _state.update { it.copy(appliedId = id, message = null) }
    }

    /** The user confirmed the presented key: persist it on the profile, then retry the connection. */
    fun trustPendingHostKey() {
        val pending = _state.value.pendingHostKey ?: return
        val updated = controller.trustHostKey(pending) ?: return
        sshProfiles.upsert(updated)
        _state.update {
            it.copy(
                sshProfiles = sshProfiles.profiles(),
                pendingHostKey = null,
            )
        }
        connect()
    }

    /** The user declined the presented key: drop it and stay disconnected. */
    fun dismissPendingHostKey() {
        controller.dismissHostKey()
        _state.update { it.copy(pendingHostKey = null) }
    }

    fun connect() {
        viewModelScope.launch { controller.connect() }
    }

    fun reconnect() {
        viewModelScope.launch { controller.reconnect() }
    }

    fun disconnect() {
        controller.disconnect()
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }
}
