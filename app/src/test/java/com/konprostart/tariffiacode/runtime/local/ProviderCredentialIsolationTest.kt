package com.konprostart.tariffiacode.runtime.local

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * AGT-3R decision guard - NOT a security control.
 *
 * Provider API keys cannot be isolated from the agent under the current architecture: the agent runs
 * as the same uid as the OpenCode server, so the plaintext `auth.json` OpenCode needs is readable by
 * any command the agent runs (see docs/SECURITY_MODEL.md).
 *
 * OpenCode's `OPENCODE_AUTH_CONTENT` (and provider env vars such as `ANTHROPIC_API_KEY`) look like an
 * alternative, but they are not isolation: the shell tool spawns commands with `{ ...process.env }`,
 * so the agent inherits and can read them. This test pins that the app does not adopt that
 * non-isolating channel while the real fix (a separate uid or an out-of-sandbox proxy) is missing.
 */
class ProviderCredentialIsolationTest {
    @Test
    fun `the server environment does not carry provider credentials`() {
        val env =
            localRuntimeEnvironment(
                suiteEnvironment = emptyMap(),
                prootTmp = File("/tmp"),
            )

        assertFalse(
            "OPENCODE_AUTH_CONTENT is inherited by every shell tool and would not isolate the key",
            env.containsKey("OPENCODE_AUTH_CONTENT"),
        )
        assertFalse("no provider API-key env vars", env.keys.any { it.contains("API_KEY", ignoreCase = true) })
        assertFalse("no provider auth env vars", env.keys.any { it.contains("AUTH", ignoreCase = true) })
    }
}
