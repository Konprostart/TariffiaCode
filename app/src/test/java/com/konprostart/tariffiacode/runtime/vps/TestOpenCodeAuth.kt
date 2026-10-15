package com.konprostart.tariffiacode.runtime.vps

import java.io.BufferedReader
import java.util.Base64

/**
 * The credential the fake OpenCode servers in this package expect.
 *
 * They are hand-rolled HTTP servers, and since the connector now refuses an endpoint that answers an
 * unauthenticated request they have to behave like a real `opencode serve` started with
 * `OPENCODE_SERVER_PASSWORD`: 401 without the header, 200 with it.
 *
 * Centralised so all four fakes agree, and so a test that needs a different one can say so instead
 * of re-deriving the Basic value.
 */
internal object TestOpenCodeAuth {
    const val USERNAME = "opencode"
    const val PASSWORD = "test-opencode-password"

    private val expected: String =
        "Basic " + Base64.getEncoder().encodeToString("$USERNAME:$PASSWORD".toByteArray())

    fun authorized(header: String?): Boolean = header == expected

    /** Drains the remaining headers and returns the `Authorization` value, if any. */
    fun drain(reader: BufferedReader): String? {
        var authorization: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Authorization:", ignoreCase = true)) {
                authorization = line.substringAfter(':').trim()
            }
        }
        return authorization
    }

    /** The status line a health request should get for the credential it carried. */
    fun healthStatus(header: String?): String = if (authorized(header)) "HTTP/1.1 200 OK\r\n" else "HTTP/1.1 401 Unauthorized\r\n"
}
