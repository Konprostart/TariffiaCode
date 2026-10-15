package com.konprostart.tariffiacode.runtime.vps

import com.konprostart.tariffiacode.core.ssh.SshPortForward
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
     * Runs the check off the main thread.
     *
     * The caller reaches this from `VpsRuntimeConnector.connect`, which is invoked from
     * `viewModelScope` - that is, from the main dispatcher. `OkHttpClient.execute` is a blocking
     * call, so without this hop a probe would freeze the UI for up to the sum of both timeouts, which
     * is the kind of stall the rest of this layer is careful never to produce.
     */
    suspend fun verify(
        forward: SshPortForward,
        username: String,
        password: String?,
    ): Outcome {
        if (password.isNullOrBlank()) return Outcome.Unauthenticated
        return withContext(Dispatchers.IO) { verifyBlocking(forward, username, password) }
    }

    private fun verifyBlocking(
        forward: SshPortForward,
        username: String,
        password: String,
    ): Outcome {
        // One client for both probes, released at the end. Building one per request would leave a
        // Dispatcher thread pool and up to five idle connections behind on every connect attempt,
        // and the recovery loop retries connects five times before giving up.
        val client = probeClient()
        try {
            val open = statusOf(client, request(forward, username, password = null))
            if (open in SUCCESS_CODES) return Outcome.Unauthenticated
            if (open != UNAVAILABLE && open !in AUTH_CHALLENGE_CODES) {
                return Outcome.Failed("OpenCode on the VPS answered the auth check with HTTP $open")
            }
            if (open == UNAVAILABLE) return Outcome.Failed(UNREACHABLE_MESSAGE)

            val authenticated = statusOf(client, request(forward, username, password))
            return when {
                authenticated in SUCCESS_CODES -> Outcome.Authenticated
                authenticated == UNAVAILABLE -> Outcome.Failed(UNREACHABLE_MESSAGE)
                authenticated in AUTH_CHALLENGE_CODES ->
                    Outcome.Failed("OpenCode on the VPS rejected the stored OPENCODE_SERVER_PASSWORD")
                else -> Outcome.Failed("OpenCode on the VPS answered the authenticated check with HTTP $authenticated")
            }
        } finally {
            release(client)
        }
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
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    private fun release(client: OkHttpClient) {
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
        runCatching { client.cache?.close() }
    }

    private fun request(
        forward: SshPortForward,
        username: String,
        password: String?,
    ): Request {
        val builder =
            Request.Builder()
                .url("http://${forward.localHost}:${forward.localPort}$HEALTH_PATH")
                .header("Accept", "application/json")
        if (password != null) builder.header("Authorization", Credentials.basic(username, password))
        return builder.get().build()
    }

    /**
     * The status code, or [UNAVAILABLE] when the exchange never completed.
     *
     * Deliberately not `runCatching(...).getOrDefault(...)`: that swallows the difference between
     * "the server said 401" and "the socket died", and it swallows `InterruptedIOException` along
     * with it, which is how a cancelled coroutine ends up with a meaningless "HTTP -1" instead of
     * being told the tunnel is unreachable.
     */
    private fun statusOf(
        client: OkHttpClient,
        request: Request,
    ): Int =
        try {
            client.newCall(request).execute().use { it.code }
        } catch (interrupted: InterruptedIOException) {
            // Subtype of IOException, so it has to be caught first. The flag is restored so the
            // coroutine machinery upstream still sees the cancellation.
            if (interrupted !is java.net.SocketTimeoutException) Thread.currentThread().interrupt()
            UNAVAILABLE
        } catch (unreachable: IOException) {
            UNAVAILABLE
        }

    private const val HEALTH_PATH = "/global/health"
    private val SUCCESS_CODES = 200..299
    private val AUTH_CHALLENGE_CODES = setOf(401, 403)

    /** Not a status code: the request never produced one. */
    private const val UNAVAILABLE = -1

    private const val UNREACHABLE_MESSAGE = "OpenCode on the VPS is unreachable through the tunnel"
    private const val TIMEOUT_SECONDS = 5L
    private const val CALL_TIMEOUT_SECONDS = 12L
}
