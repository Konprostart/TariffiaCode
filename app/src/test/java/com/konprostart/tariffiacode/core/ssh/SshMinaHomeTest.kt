package com.konprostart.tariffiacode.core.ssh

import org.apache.sshd.common.util.io.PathUtils
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SshMinaHomeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `installs a user home that MINA can resolve`() {
        val home = File(temporaryFolder.root, "ssh-home")

        installSshUserHomeFolder(home)

        assertEquals(home.toPath(), PathUtils.getUserHomeFolder())
    }
}
