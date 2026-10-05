package com.konprostart.tariffiacode.feature.remote

import androidx.lifecycle.ViewModel
import com.konprostart.tariffiacode.data.remote.RemoteProject
import com.konprostart.tariffiacode.data.remote.RemoteProjectStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.data.ssh.SshProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
    val form: RemoteProjectForm? = null,
    val message: String? = null,
)

/**
 * Backs the Remote Project screen: lists mappings, edits one, and applies a mapping to the VPS runtime.
 *
 * Applying is delegated to [onApply] (supplied by the navigation layer) so this ViewModel stays free of
 * any dependency on the runtime target.
 */
class RemoteProjectViewModel(
    private val store: RemoteProjectStore,
    private val sshProfiles: SshProfileStore,
    private val onApply: (RemoteProject, SshProfile) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<RemoteProjectUiState> = _state.asStateFlow()

    private fun loadState(): RemoteProjectUiState {
        val projects = store.projects()
        return RemoteProjectUiState(
            projects = projects,
            sshProfiles = sshProfiles.profiles(),
            danglingIds = store.danglingProjects().map { it.id }.toSet(),
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

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }
}
