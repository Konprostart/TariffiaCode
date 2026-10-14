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
}
