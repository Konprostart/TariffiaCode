package com.konprostart.tariffiacode.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * A mapping from a project identity to a remote workspace on a VPS.
 *
 * It is ONLY a mapping: which project, which existing SSH connection, and where that project lives on the
 * remote filesystem. It is not an agent, not a Git implementation, not an SSH implementation, and it holds
 * no credentials — it references an existing [com.konprostart.tariffiacode.data.ssh.SshProfile] by id, and
 * the secret for that profile stays in the encrypted credential store.
 *
 * @param id stable identity.
 * @param displayName optional user-facing label; falls back to [projectRef] in the UI.
 * @param projectRef the project identity/reference (e.g. a repository full name or a project key).
 * @param sshProfileId id of the existing SSH connection that reaches the VPS.
 * @param remotePath absolute path of the project workspace on the VPS.
 */
@Serializable
data class RemoteProject(
    @SerialName("id") val id: String = UUID.randomUUID().toString(),
    @SerialName("displayName") val displayName: String = "",
    @SerialName("projectRef") val projectRef: String,
    @SerialName("sshProfileId") val sshProfileId: String,
    @SerialName("remotePath") val remotePath: String,
) {
    /** Label to show for this mapping. */
    val label: String get() = displayName.trim().ifBlank { projectRef.trim() }
}

object RemoteProjectCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    fun encode(projects: List<RemoteProject>): String = json.encodeToString(projects)

    fun decode(jsonString: String): List<RemoteProject> {
        if (jsonString.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<RemoteProject>>(jsonString) }.getOrDefault(emptyList())
    }
}
