package com.konprostart.tariffiacode.runtime.local

import java.io.File
import java.security.SecureRandom

/**
 * The password that protects the local OpenCode server's loopback HTTP API.
 *
 * Android apps share the `127.0.0.1` interface, so an unauthenticated `opencode serve` on a fixed
 * port is reachable by any other app on the device. OpenCode already supports HTTP Basic auth via
 * `OPENCODE_SERVER_PASSWORD`/`OPENCODE_SERVER_USERNAME`; this holds the random per-start password the
 * app passes to the server and sends back as a client.
 *
 * The secret is generated fresh on every server start ([rotate]) and kept in a file in the
 * app-private runtime directory - outside the rootfs and workspace, so it is never written into the
 * agent sandbox - and is never logged.
 */
object LocalRuntimeServerSecret {
    /** OpenCode's default Basic-auth username; kept explicit so the client and server agree. */
    const val USERNAME = "opencode"

    const val ENV_PASSWORD = "OPENCODE_SERVER_PASSWORD"
    const val ENV_USERNAME = "OPENCODE_SERVER_USERNAME"

    private const val FILE_NAME = "server-secret"
    private const val SECRET_BYTES = 32

    private val random = SecureRandom()

    /** The current secret, or null when the server has never been started in this runtime. */
    fun read(runtimeDirectory: File): String? =
        File(runtimeDirectory, FILE_NAME)
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /**
     * Generates a new secret, persists it app-privately, and returns it. Called on every server
     * start so a restarted server never reuses the previous password.
     */
    fun rotate(runtimeDirectory: File): String {
        val secret = newSecret()
        val file = File(runtimeDirectory, FILE_NAME)
        file.parentFile?.mkdirs()
        file.writeText(secret)
        // Best effort: the runtime directory is already app-private, but keep the file owner-only.
        runCatching {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
        return secret
    }

    fun clear(runtimeDirectory: File) {
        File(runtimeDirectory, FILE_NAME).delete()
    }

    /** A 256-bit random value, hex-encoded so it is URL/header/JSON safe. */
    fun newSecret(): String {
        val bytes = ByteArray(SECRET_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
