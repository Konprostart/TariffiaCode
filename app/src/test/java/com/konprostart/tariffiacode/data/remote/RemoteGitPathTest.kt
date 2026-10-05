package com.konprostart.tariffiacode.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteGitPathTest {
    @Test
    fun `absolute path is valid`() {
        assertNull(RemoteGitPath.validate("/root/projects/TariffiaCode"))
        assertTrue(RemoteGitPath.isValid("/root/projects/TariffiaCode"))
    }

    @Test
    fun `empty relative and traversing paths are rejected`() {
        assertEquals("missing", RemoteGitPath.validate(" "))
        assertEquals("notAbsolute", RemoteGitPath.validate("root/projects"))
        assertEquals("invalid", RemoteGitPath.validate("/root/../etc"))
    }

    @Test
    fun `shell metacharacters are rejected`() {
        assertFalse(RemoteGitPath.isValid("/root/projects;rm -rf /"))
        assertFalse(RemoteGitPath.isValid("/root/pro\\jects"))
    }

    @Test
    fun `shell quoting neutralises embedded single quotes`() {
        assertEquals("'a'\\''b'", RemoteGitPath.shellQuote("a'b"))
    }
}
