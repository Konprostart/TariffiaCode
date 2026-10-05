package com.konprostart.tariffiacode.data.remote

import com.konprostart.tariffiacode.data.connection.SecureSettingsRepository
import com.konprostart.tariffiacode.data.ssh.SshProfileStore

/**
 * CRUD and validation for [RemoteProject] mappings, backed by the app's existing encrypted preferences.
 *
 * The store only ever holds the mapping (project, SSH profile id, remote path). It never reads or writes a
 * secret; the referenced SSH profile owns its own credential in the encrypted credential store.
 *
 * Validation is delegated to [validate] so the UI and the store agree on what "valid" means, and so an
 * SSH profile that has been deleted leaves a mapping that resolves as dangling rather than silently usable.
 */
class RemoteProjectStore(
    private val load: () -> List<RemoteProject>,
    private val save: (List<RemoteProject>) -> Unit,
    private val sshProfileExists: (String) -> Boolean,
) {
    constructor(
        settings: SecureSettingsRepository,
        sshProfiles: SshProfileStore,
    ) : this(
        load = { settings.remoteProjects },
        save = { settings.remoteProjects = it },
        sshProfileExists = { id -> sshProfiles.profile(id) != null },
    )

    fun projects(): List<RemoteProject> = load()

    fun project(id: String): RemoteProject? = projects().firstOrNull { it.id == id }

    /**
     * Insert or replace [project].
     *
     * @return a map of field name → error key for every invalid field; empty when the mapping was saved.
     */
    fun upsert(project: RemoteProject): Map<String, String> {
        val errors = validate(project)
        if (errors.isNotEmpty()) return errors
        val current = projects()
        val index = current.indexOfFirst { it.id == project.id }
        val updated =
            if (index >= 0) {
                current.toMutableList().also { it[index] = project }
            } else {
                current + project
            }
        save(updated)
        return emptyMap()
    }

    fun delete(id: String) {
        val current = projects()
        val updated = current.filterNot { it.id == id }
        if (updated.size != current.size) save(updated)
    }

    /** Mappings whose referenced SSH profile no longer exists. */
    fun danglingProjects(): List<RemoteProject> = projects().filter { !sshProfileExists(it.sshProfileId) }

    /** True when [id] refers to an existing mapping whose SSH profile still exists. */
    fun isResolvable(id: String): Boolean = project(id)?.let { sshProfileExists(it.sshProfileId) } == true

    /** Drop every mapping pointing at a missing SSH profile. Returns the removed mappings. */
    fun removeDangling(): List<RemoteProject> {
        val dangling = danglingProjects()
        if (dangling.isNotEmpty()) save(projects().filterNot { it in dangling })
        return dangling
    }

    /** Field-level validation. Empty means valid. */
    fun validate(project: RemoteProject): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        if (project.projectRef.isBlank()) errors[FIELD_PROJECT] = "missing"
        if (project.sshProfileId.isBlank()) {
            errors[FIELD_SSH_PROFILE] = "missing"
        } else if (!sshProfileExists(project.sshProfileId)) {
            errors[FIELD_SSH_PROFILE] = "unknown"
        }
        when {
            project.remotePath.isBlank() -> errors[FIELD_REMOTE_PATH] = "missing"
            !isAbsolutePath(project.remotePath.trim()) -> errors[FIELD_REMOTE_PATH] = "notAbsolute"
        }
        return errors
    }

    companion object {
        const val FIELD_PROJECT = "project"
        const val FIELD_SSH_PROFILE = "sshProfile"
        const val FIELD_REMOTE_PATH = "remotePath"

        /** A remote path must be absolute and must not traverse upwards. */
        fun isAbsolutePath(path: String): Boolean {
            val trimmed = path.trim()
            if (!trimmed.startsWith("/")) return false
            val segments = trimmed.split('/')
            return segments.none { it == ".." }
        }
    }
}
