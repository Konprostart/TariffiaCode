package com.konprostart.tariffiacode.runtime.local

/**
 * Decides which credentials may be written into the long-lived agent sandbox (the local runtime).
 *
 * Secrets live in the app's encrypted store; the runtime needs some of them to work. This policy keeps
 * the most sensitive, standing exposure (the GitHub token in the process environment and
 * `~/.git-credentials`) off unless the user has explicitly opted in. App-run operations such as cloning
 * inject the token themselves for that single command, so they are unaffected.
 */
object RuntimeCredentialPolicy {
    /**
     * The GitHub token to expose to the long-lived runtime, or null when the user has not opted in (or
     * no token is configured). Null means the token is never written to the sandbox environment or the
     * git credential store.
     */
    fun runtimeGitHubToken(
        token: String?,
        shareWithRuntime: Boolean,
    ): String? = token?.takeIf { shareWithRuntime && it.isNotBlank() }
}
