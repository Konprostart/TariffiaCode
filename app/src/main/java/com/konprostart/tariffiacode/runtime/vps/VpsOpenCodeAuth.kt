package com.konprostart.tariffiacode.runtime.vps

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * Proves that the OpenCode server behind a VPS forward is actually authenticated, before the app
 * points a user's agent at it.
 *
 * The check is two requests over the tunnel's loopback:
 *
 *  1. **Unauthenticated.** A `GET /global/health` with no `Authorization` header. If this *succeeds*,
 *     the server is open to every app on the device - Android shares `127.0.0.1` between apps, so an
 *     open port here is a remote shell and a set of provider keys to anything else installed, not
 *     just a slow endpoint. That is [Outcome.Unauthenticated], and the connector refuses it.
 *  2. **Authenticated.** The same request carrying HTTP Basic credentials. A 401/403 here means the
 *     server is protected but the profile's password is wrong or unset, which is a different problem
 *     with a different fix, so it is reported separately rather than as "open".
 *
 * The second request is what proves the endpoint is *usable* rather than merely locked, and running
 * both means a server cannot be refused for being locked when the real problem is the password.
 *
 * A connection that cannot be established at all is [Outcome.Failed], not [Outcome.Unauthenticated]:
 * refusing to route because the VPS is unreachable would be a worse failure mode than the one being
 * fixed, and the app's existing health check already reports that state properly.
 *
 * The probe is a health endpoint. It cannot spend a provider call, change any state, or reach the
 * filesystem, so it is safe to run before the caller has decided to keep the tunnel.
 */
internal object VpsOpenCodeAuth {
    sealed interface Outcome {
        /** The server requires a password and accepts ours. */
        data object Authenticated : Outcome

        /** The server answered a request that carried no credentials. */
        data object Unauthenticated : Outcome

        /** The tunnel is up but the server could not be reached or answered unexpectedly. */
        data class Failed(
            val message: String,
        ) : Outcome
    }

    /**
     * A short-lived client for a single loopback probe.
     *
     * Deliberately not the app's shared OkHttp instance: this runs inside a connection attempt that
     * the caller may abandon, and it must not be able to delay that on a pool the chat UI is also
     * using. Timeouts are short for the same reason - this blocks the connect path.
     */
    private fun probeClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    fun verify(
        forward: SshPortForward,
        username: String,
        password: String?,
    ): Outcome {
        if (password.isNullOrBlank()) return Outcome.Unauthenticated
        val client = probeClient()
        return try {
            val open = statusOf(client, request(forward, username = null, password = null))
            when {
                open in SUCCESS_CODES -> Outcome.Unauthenticated
                open !in SUCCESS_CODES && open !in AUTH_CHALLENGE_CODES ->
                    Outcome.Failed("OpenCode on the VPS answered the auth check with HTTP $open")
                else -> {
                    val authenticated = statusOf(client, request(forward, username, password))
                    when {
                        authenticated in SUCCESS_CODES -> Outcome.Authenticated
                        authenticated in AUTH_CHALLENGE_CODES ->
                            Outcome.Failed(
                                "OpenCode on the VPS rejected the stored OPENCODE_SERVER_PASSWORD",
                            )
                        else ->
                            Outcome.Failed(
                                "OpenCode on the VPS answered the authenticated check with HTTP $authenticated",
                            )
                    }
                }
            }
        } catch (interrupted: InterruptedIOException) {
            // Must come before IOException, which is its supertype, and the interrupt flag has to be
            // restored or nothing upstream sees that the coroutine was cancelled.
            Thread.currentThread().interrupt()
            Outcome.Failed("The OpenCode auth check on the VPS was interrupted")
        } catch (unreachable: IOException) {
            // The forward is open; nothing is listening behind it. That is a configuration or
            // reachability problem, and the caller already has a state for it.
            Outcome.Failed("OpenCode on the VPS is unreachable through the tunnel")
        }
    }

    private fun request(
        forward: SshPortForward,
        username: String?,
        password: String?,
    ): Request {
        val builder =
            Request.Builder()
                .url("http://${forward.localHost}:${forward.localPort}/global/health")
                .header("Accept", "application/json")
        if (password != null) builder.header("Authorization", Credentials.basic(username, password))
        return builder.get().build()
    }

    /** The status code, or [IO_ERROR] when the response could not be read at all. */
    private fun statusOf(
        client: OkHttpClient,
        request: Request,
    ): Int =
        runCatching { client.newCall(request).execute().use { response -> response.code } }
            .getOrDefault(IO_ERROR)

    private val SUCCESS_CODES = 200..299
    private val AUTH_CHALLENGE_CODES = setOf(401, 403)

    private const val IO_ERROR = -1
    private const val CONNECT_TIMEOUT_SECONDS = 5L
    private const val READ_TIMEOUT_SECONDS = 5L
}
