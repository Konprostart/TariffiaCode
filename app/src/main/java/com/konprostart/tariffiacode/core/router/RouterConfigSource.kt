package com.konprostart.tariffiacode.core.router

import java.io.File

/**
 * Works out which routing table the router should use, and says which one it chose.
 *
 * The table ships inside the app, which makes it awkward in exactly the way that matters: adding a
 * model means shipping a new APK. Adding a model should be data, not a release.
 *
 * So a file the user can reach takes precedence over the bundled one, and the bundled one stays as
 * the default rather than as a template the user has to start from. If the user's file is missing,
 * unreadable, or does not describe anything that could serve a turn, the router falls back to the
 * bundled table and says why - because silently serving something other than what was edited is the
 * failure that produces "I changed it and nothing happened".
 */
object RouterConfigSource {
    /** Where the table came from, so a surprising one can be explained instead of guessed at. */
    enum class Origin {
        /** A file the user can edit. */
        USER_FILE,

        /** The copy shipped inside the app. */
        BUNDLED,
    }

    /**
     * The table to run, where it came from, and what was wrong with what was not used.
     *
     * [rejected] is non-empty only when a user file was found and not used. That is the case worth
     * shouting about: the user believes they are running their own table and are not.
     */
    data class Loaded(
        val table: RouterConfigTable.Result,
        val origin: Origin,
        val rejected: String? = null,
    ) {
        val isUsable: Boolean get() = table.isUsable
    }

    /**
     * Picks between the two tables.
     *
     * Takes readers rather than files so the rule can be tested without an Android device, and so the
     * Android class is left with nothing but knowing where the two files live.
     *
     * A user file is only preferred when it parses *and* still has something in it. An override that
     * leaves every chain empty is treated as no override at all: the user almost certainly deleted
     * too much, and serving nothing would look like the router had broken.
     */
    fun resolve(
        readUserTable: () -> String?,
        readBundledTable: () -> String,
    ): Loaded {
        val userText = runCatching { readUserTable() }.getOrNull()
        if (userText != null) {
            val parsed = RouterConfigTable.fromJson(userText)
            if (parsed.isUsable) return Loaded(parsed, Origin.USER_FILE)
            return Loaded(
                bundled(readBundledTable),
                Origin.BUNDLED,
                "user table rejected: ${parsed.rejected.joinToString(", ")}",
            )
        }
        return Loaded(bundled(readBundledTable), Origin.BUNDLED)
    }

    private fun bundled(read: () -> String): RouterConfigTable.Result =
        runCatching { read() }.fold(
            onSuccess = { RouterConfigTable.fromJson(it) },
            onFailure = { failure -> RouterConfigTable.Result(RouterConfig(), listOf("unreadable: ${failure.message}")) },
        )

    /**
     * Where a user-editable table lives.
     *
     * The app-specific external directory, because it is the one place on modern Android that a file
     * manager can reach without the app asking for a storage permission, and that Android removes on
     * uninstall so a deleted app does not leave routing configuration behind.
     */
    fun userTableFile(
        externalFilesDir: File?,
        fileName: String,
    ): File? = externalFilesDir?.resolve("router/$fileName")
}
