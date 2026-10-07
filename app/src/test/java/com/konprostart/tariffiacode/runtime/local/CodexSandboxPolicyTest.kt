package com.konprostart.tariffiacode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Test

class CodexSandboxPolicyTest {
    @Test
    fun `full access is denied by default`() {
        assertEquals(CodexSandboxPolicy.WORKSPACE_WRITE, CodexSandboxPolicy.sandboxMode(fullAccessEnabled = false))
    }

    @Test
    fun `full access is granted only after opt-in`() {
        assertEquals(CodexSandboxPolicy.DANGER_FULL_ACCESS, CodexSandboxPolicy.sandboxMode(fullAccessEnabled = true))
    }
}
