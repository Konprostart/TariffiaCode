package com.konprostart.tariffiacode.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteGitCloneValidatorTest {
    private fun request(
        url: String = "https://github.com/Konprostart/TariffiaCode.git",
        path: String = "/root/projects/TariffiaCode",
    ) = RemoteGitCloneRequest(url, path)

    @Test
    fun `valid request has no errors`() {
        assertTrue(RemoteGitCloneValidator.validate(request()).isEmpty())
        assertTrue(RemoteGitCloneValidator.isValid(request()))
    }

    @Test
    fun `missing and invalid repository url are rejected`() {
        assertTrue(RemoteGitCloneValidator.validate(request(url = " ")).containsKey(RemoteGitCloneValidator.FIELD_URL))
        assertTrue(
            RemoteGitCloneValidator.validate(request(url = "https://x/a b"))
                .containsKey(RemoteGitCloneValidator.FIELD_URL),
        )
    }

    @Test
    fun `missing non absolute and traversing paths are rejected`() {
        val v = RemoteGitCloneValidator
        assertTrue(v.validate(request(path = " ")).containsKey(v.FIELD_PATH))
        assertTrue(v.validate(request(path = "root/projects")).containsKey(v.FIELD_PATH))
        assertTrue(v.validate(request(path = "/root/../etc")).containsKey(v.FIELD_PATH))
    }

    @Test
    fun `shell quoting neutralises embedded single quotes`() {
        assertEquals("'a'\\''b'", RemoteGitCloneValidator.shellQuote("a'b"))
    }

    @Test
    fun `invalid characters in path are rejected`() {
        assertFalse(RemoteGitCloneValidator.isValid(request(path = "/root/projects;rm -rf /")))
    }
}
