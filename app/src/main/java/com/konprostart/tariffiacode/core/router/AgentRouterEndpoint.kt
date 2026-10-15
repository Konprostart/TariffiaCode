package com.konprostart.tariffiacode.core.router

import java.io.File

/**
 * How an agent on this device is told where the router is.
 *
 * The router writes its address where the guest can read it; this reads it back and turns it into
 * the two environment variables a client needs. That is the whole integration - the agent is not
 * taught about routing, it is handed a base URL and a token like any other gateway.
 *
 * Absent or unreadable yields nothing, and the agent then uses whatever it would have used anyway.
 * A router that is not running must never be able to stop an agent from starting.
 */
object AgentRouterEndpoint {
    /**
     * The variables for [runtimeDirectory], or empty when the router is not serving.
     *
     * Not thrown on: a missing file is the ordinary state when routing is switched off, and a
     * malformed one is the router's problem to report, not the agent's to fail on.
     */
    fun environmentFor(runtimeDirectory: File): Map<String, String> {
        val file = File(runtimeDirectory, ENDPOINT_FILE_NAME)
        if (!file.isFile) return emptyMap()
        return runCatching {
            val values =
                file.readLines().mapNotNull { line ->
                    val value = line.substringAfter('=', "")
                    if (value.isBlank()) null else line.substringBefore('=') to value
                }.toMap()
            val baseUrl = values["ANTHROPIC_BASE_URL"]
            val token = values["ANTHROPIC_AUTH_TOKEN"]
            if (baseUrl.isNullOrBlank() || token.isNullOrBlank()) {
                emptyMap()
            } else {
                mapOf(
                    "ANTHROPIC_BASE_URL" to baseUrl,
                    "ANTHROPIC_AUTH_TOKEN" to token,
                )
            }
        }.getOrDefault(emptyMap())
    }

    /** Where [RouterService] writes it. Named here so the two cannot drift apart. */
    const val ENDPOINT_FILE_NAME = "router-endpoint.txt"

    /**
     * The loopback port [file] names, or null when it names nothing usable.
     *
     * Null covers every way the file can be unhelpful: absent, empty, not a file at all, a line
     * without a value, no base URL, a base URL with no port, a port that is not a number, or a port
     * outside the range a socket can bind. Anything that survives this is a port somebody once bound.
     *
     * On its own this is not evidence that the router is serving - the file outlives the process that
     * wrote it - which is why [decideStatus] takes the second answer separately.
     */
    fun portOf(file: File): Int? {
        if (!file.isFile) return null
        val values =
            runCatching {
                file.readLines().mapNotNull { line ->
                    val value = line.substringAfter('=', "")
                    if (value.isBlank()) null else line.substringBefore('=') to value
                }.toMap()
            }.getOrDefault(emptyMap())
        val url = values["ANTHROPIC_BASE_URL"] ?: return null
        val port = url.substringAfterLast(':').trim().toIntOrNull() ?: return null
        return port.takeIf { it in 1..65535 }
    }

    /**
     * What the screen should show, given a parsed port and whether anything is really listening.
     *
     * Split out from [RouterService] because this is the whole honesty question and it needs to be
     * testable without an Android context: the file says which port to check, and the probe says
     * whether a server is on it. Reporting [RouterStatus.Running] for a port nothing accepts is the
     * failure that lets a stopped router look alive, so the two inputs are never collapsed into one.
     */
    fun decideStatus(
        port: Int?,
        accepting: Boolean,
    ): RouterStatus = if (port != null && accepting) RouterStatus.Running(port) else RouterStatus.NotRunning
}
