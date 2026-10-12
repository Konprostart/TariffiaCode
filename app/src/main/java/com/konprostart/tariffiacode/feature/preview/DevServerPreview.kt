package com.konprostart.tariffiacode.feature.preview

/**
 * Finds the dev-server ports a chat message advertises, so the chat can offer a one-tap live preview
 * instead of asking the user to retype the port.
 *
 * Matches a loopback host with a port, with or without a scheme - the shapes agents print when they
 * start a dev server: `http://localhost:5173/`, `https://127.0.0.1:3000`, `localhost:8080`,
 * `0.0.0.0:5174`. The OpenCode server's own port (4096) is the app's tunnel target, never a preview,
 * so it is excluded.
 */
object DevServerPreview {
    private val LOOPBACK_URL = Regex("""(?i)(?:https?://)?(?:localhost|127\.0\.0\.1|0\.0\.0\.0):(\d{2,5})\b""")

    /** The OpenCode HTTP server port; a preview of it would be meaningless. */
    private const val OPEN_CODE_PORT = 4096

    private const val MAX_RESULTS = 4

    /** Distinct dev-server ports in first-seen order, newest-safe bounded, OpenCode excluded. */
    fun detectPorts(text: String): List<Int> =
        LOOPBACK_URL
            .findAll(text)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.toIntOrNull() }
            .filter { port -> port in 1..65535 && port != OPEN_CODE_PORT }
            .distinct()
            .take(MAX_RESULTS)
            .toList()
}
