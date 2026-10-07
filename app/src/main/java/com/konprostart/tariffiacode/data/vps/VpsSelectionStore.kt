package com.konprostart.tariffiacode.data.vps

import com.konprostart.tariffiacode.data.connection.SecureSettingsRepository

/**
 * Persists which SSH profile and Remote Project mapping the single VPS runtime is pointed at.
 *
 * The profile and mapping records themselves live in [com.konprostart.tariffiacode.data.ssh.SshProfileStore]
 * and [com.konprostart.tariffiacode.data.remote.RemoteProjectStore]; this only remembers *which* ids are
 * selected/applied, so reopening the app or coming back from process death re-points the VPS runtime at
 * the same server and folder instead of falling back to "no SSH connection selected".
 *
 * The load/save lambdas are injected so the logic is unit-testable without Android.
 */
class VpsSelectionStore(
    private val loadProfileId: () -> String?,
    private val saveProfileId: (String?) -> Unit,
    private val loadProjectId: () -> String?,
    private val saveProjectId: (String?) -> Unit,
) {
    constructor(settings: SecureSettingsRepository) : this(
        loadProfileId = { settings.selectedVpsProfileId },
        saveProfileId = { settings.selectedVpsProfileId = it },
        loadProjectId = { settings.appliedRemoteProjectId },
        saveProjectId = { settings.appliedRemoteProjectId = it },
    )

    fun selectedProfileId(): String? = loadProfileId()

    fun selectedProjectId(): String? = loadProjectId()

    fun selectProfile(id: String?) = saveProfileId(id)

    fun selectProject(id: String?) = saveProjectId(id)
}
