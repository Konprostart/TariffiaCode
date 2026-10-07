package com.konprostart.tariffiacode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeCredentialPolicyTest {
    @Test
    fun `github token is not exposed to the runtime by default`() {
        // Denied: no explicit opt-in, so the token must never reach the sandbox env/git store.
        assertNull(RuntimeCredentialPolicy.runtimeGitHubToken("ghp_secret", shareWithRuntime = false))
    }

    @Test
    fun `github token is exposed only after explicit opt-in`() {
        assertEquals("ghp_secret", RuntimeCredentialPolicy.runtimeGitHubToken("ghp_secret", shareWithRuntime = true))
    }

    @Test
    fun `no token configured stays null regardless of opt-in`() {
        assertNull(RuntimeCredentialPolicy.runtimeGitHubToken(null, shareWithRuntime = true))
        assertNull(RuntimeCredentialPolicy.runtimeGitHubToken(null, shareWithRuntime = false))
        assertNull(RuntimeCredentialPolicy.runtimeGitHubToken("   ", shareWithRuntime = true))
    }
}
