package com.konprostart.tariffiacode.data.remote

/**
 * Decides which OpenCode `directory` a VPS runtime should use for a session or a listing.
 *
 * An explicit [requestedDirectory] always wins: the user's chosen workspace is authoritative. Only when
 * none is given does a [mapping] that belongs to the currently selected SSH profile contribute its remote
 * path. A mapping whose SSH profile is not the selected one is ignored, so a stale mapping cannot leak a
 * path from another server.
 */
object RemoteProjectResolver {
    fun resolveDirectory(
        mapping: RemoteProject?,
        selectedSshProfileId: String?,
        requestedDirectory: String?,
    ): String? {
        val requested = requestedDirectory?.trim().orEmpty()
        if (requested.isNotEmpty()) return requested
        if (mapping == null || selectedSshProfileId.isNullOrBlank()) return null
        if (mapping.sshProfileId != selectedSshProfileId) return null
        return mapping.remotePath.trim().takeIf { it.isNotEmpty() }
    }
}
