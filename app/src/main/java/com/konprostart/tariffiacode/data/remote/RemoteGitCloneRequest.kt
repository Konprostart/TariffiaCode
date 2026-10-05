package com.konprostart.tariffiacode.data.remote

/** A request to clone a Git repository onto a VPS at [remotePath]. */
data class RemoteGitCloneRequest(
    val repositoryUrl: String,
    val remotePath: String,
)

/**
 * Validation and shell-quoting for a [RemoteGitCloneRequest].
 *
 * The URL and path are checked against a strict charset and then single-quoted before they are placed in
 * the remote shell command, so a crafted value cannot inject shell syntax. Paths must be absolute and must
 * not traverse upwards.
 */
object RemoteGitCloneValidator {
    const val FIELD_URL = "repositoryUrl"
    const val FIELD_PATH = "remotePath"

    // No whitespace, quotes or shell metacharacters: keeps the value safe to single-quote and predictable.
    private val URL_PATTERN = Regex("^[A-Za-z0-9._~:/@%+\\-]+$")
    private val PATH_PATTERN = Regex("^/[A-Za-z0-9._~@%+\\-/]+$")

    fun validate(request: RemoteGitCloneRequest): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        val url = request.repositoryUrl.trim()
        when {
            url.isEmpty() -> errors[FIELD_URL] = "missing"
            !URL_PATTERN.matches(url) -> errors[FIELD_URL] = "invalid"
        }
        val path = request.remotePath.trim()
        when {
            path.isEmpty() -> errors[FIELD_PATH] = "missing"
            !path.startsWith("/") -> errors[FIELD_PATH] = "notAbsolute"
            !PATH_PATTERN.matches(path) || path.split('/').any { it == ".." } -> errors[FIELD_PATH] = "invalid"
        }
        return errors
    }

    fun isValid(request: RemoteGitCloneRequest): Boolean = validate(request).isEmpty()

    /** POSIX single-quote a value so it can be embedded in a shell command literally. */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
