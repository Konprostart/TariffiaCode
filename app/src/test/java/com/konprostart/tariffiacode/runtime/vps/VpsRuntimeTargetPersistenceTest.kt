package com.konprostart.tariffiacode.runtime.vps

import com.konprostart.tariffiacode.core.ssh.MinaSshPortForwarder
import com.konprostart.tariffiacode.data.remote.RemoteProject
import com.konprostart.tariffiacode.data.remote.RemoteProjectStore
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.data.ssh.SshProfileStore
import com.konprostart.tariffiacode.data.vps.VpsSelectionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The VPS runtime's selected SSH profile and applied Remote Project mapping used to live only in
 * memory, so a restart or process death dropped them and the runtime fell back to "no SSH connection
 * selected". They are now persisted by id and restored on construction.
 */
class VpsRuntimeTargetPersistenceTest {
    private fun credentialStore() = SshCredentialStore(load = { emptyMap() }, save = {})

    private fun profile(id: String) =
        SshProfile(
            id = id,
            name = id,
            host = "example.com",
            username = "root",
            authType = SshAuthType.PASSWORD,
            credentialRef = "ref-$id",
        )

    private fun mapping(
        id: String,
        profileId: String,
    ) = RemoteProject(id = id, projectRef = "proj-$id", sshProfileId = profileId, remotePath = "/srv/$id")

    private fun connector() = VpsRuntimeConnector(MinaSshPortForwarder(), credentialStore())

    private fun profileStore(profiles: MutableList<SshProfile>) =
        SshProfileStore(
            load = { profiles.toList() },
            save = {
                profiles.clear()
                profiles.addAll(it)
            },
            credentials = credentialStore(),
        )

    private fun projectStore(mappings: MutableList<RemoteProject>) =
        RemoteProjectStore(
            load = { mappings.toList() },
            save = {
                mappings.clear()
                mappings.addAll(it)
            },
            sshProfileExists = { true },
        )

    @Test
    fun `the selected profile and applied mapping survive a recreate`() {
        val profiles = mutableListOf(profile("p1"), profile("p2"))
        val mappings = mutableListOf(mapping("m1", "p2"))
        var profileId: String? = null
        var projectId: String? = null
        val selection = VpsSelectionStore({ profileId }, { profileId = it }, { projectId }, { projectId = it })

        val first =
            VpsRuntimeTarget(
                connector(),
                profiles = profileStore(profiles),
                projects = projectStore(mappings),
                selection = selection,
            )
        first.selectProfile(profiles[1])
        first.selectRemoteProject(mappings[0])

        assertEquals("p2", profileId)
        assertEquals("m1", projectId)

        // A brand new target over the same persisted state = a restart / process death.
        val second =
            VpsRuntimeTarget(
                connector(),
                profiles = profileStore(profiles),
                projects = projectStore(mappings),
                selection = selection,
            )

        assertEquals("p2", second.selectedProfile.value?.id)
        assertEquals("m1", second.selectedRemoteProject.value?.id)
        assertEquals("/srv/m1", second.selectedRemoteProject.value?.remotePath)
    }

    @Test
    fun `two profiles stay independent and each keeps its own credential reference`() {
        val profiles = mutableListOf(profile("p1"), profile("p2"))
        var profileId: String? = null
        val selection = VpsSelectionStore({ profileId }, { profileId = it }, { null }, {})
        val target =
            VpsRuntimeTarget(
                connector(),
                profiles = profileStore(profiles),
                projects = projectStore(mutableListOf()),
                selection = selection,
            )

        target.selectProfile(profiles[0])
        assertEquals("ref-p1", target.selectedProfile.value?.credentialRef)

        target.selectProfile(profiles[1])
        assertEquals("ref-p2", target.selectedProfile.value?.credentialRef)
        assertEquals("p2", profileId)
    }

    @Test
    fun `a persisted selection whose profile was deleted is cleared, not resurrected`() {
        var profileId: String? = "gone"
        var projectId: String? = "gone"
        val selection = VpsSelectionStore({ profileId }, { profileId = it }, { projectId }, { projectId = it })
        val emptyProfiles =
            SshProfileStore(load = { emptyList() }, save = {}, credentials = credentialStore())
        val emptyProjects =
            RemoteProjectStore(load = { emptyList() }, save = {}, sshProfileExists = { false })

        VpsRuntimeTarget(connector(), profiles = emptyProfiles, projects = emptyProjects, selection = selection)

        assertNull(profileId)
        assertNull(projectId)
    }
}
