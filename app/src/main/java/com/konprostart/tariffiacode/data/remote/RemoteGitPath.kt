package com.konprostart.tariffiacode.data.remote

/**
 * Validation and shell-quoting for an absolute remote path used by a remote git command.
 *
 * Kept separate from the clone request so a path-only operation (e.g. `git pull`) can validate and quote
 * it identically without depending on a repository URL.
 */
object RemoteGitPath {
    const val FIELD_PATH = "remotePath"

    // Absolute, no whitespace/quotes/shell metacharacters: safe to single-quote and predictable.
    private val PATH_PATTERN = Regex("^/[A-Za-z0-9._~@%+\\-/]+$")

    /** @return an error key, or null when [path] is a valid absolute remote path. */
    fun validate(path: String): String? {
        val trimmed = path.trim()
        return when {
            trimmed.isEmpty() -> "missing"
            !trimmed.startsWith("/") -> "notAbsolute"
            !PATH_PATTERN.matches(trimmed) || trimmed.split('/').any { it == ".." } -> "invalid"
            else -> null
        }
    }

    fun isValid(path: String): Boolean = validate(path) == null

    /** POSIX single-quote a value so it can be embedded in a shell command literally. */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
