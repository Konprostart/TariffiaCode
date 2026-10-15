package com.konprostart.tariffiacode.core.router

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.konprostart.tariffiacode.MainActivity
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Runs the router inside the app process, so an agent on this device can reach it on loopback.
 *
 * Nothing here knows about agents. It starts a server, hands out its address, and gets out of the way;
 * that separation is what lets the router be pointed at from anywhere without this file changing.
 *
 * The address is written where the guest can read it rather than only to the log, because the whole
 * point is that something *else* - a process in the userland - needs to dial in. The file holds a
 * live bearer token, so it lives inside the app's private directory and is deleted on stop: any
 * process that can read it is already root on the device, which is the only kind of process that has
 * any business reaching the router.
 */
class RouterService : Service() {
    private var server: RouterServer? = null

    /** False when the system refused foreground promotion; the service must not keep starting. */
    private var runningInForeground = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Promotion can be refused - Android 12+ refuses a sticky restart that lands while the app is
        // in the background - and uncaught it kills :routerproc, which START_STICKY then restarts:
        // a crash loop the user never sees and no failure file records. The other three services in
        // this app already guard this call the same way.
        runCatching { startForeground(NOTIFICATION_ID, notification("starting")) }
            .onSuccess { runningInForeground = true }
            .onFailure {
                Log.e(TAG, "foreground promotion refused; refusing to serve", it)
                stopSelf()
            }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Router", NotificationManager.IMPORTANCE_LOW),
            )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (!runningInForeground) return START_NOT_STICKY
        if (server != null) return START_STICKY
        start()
        return START_STICKY
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Starts the server, or records why it could not.
     *
     * Every failure here used to escape [onStartCommand]: a ServerSocket that could not bind, a key
     * store that would not open, a disk that was full writing the endpoint file. Any of them killed
     * :routerproc, START_STICKY restarted it, and the retry died the same way - a crash loop with no
     * failure file, so the screen showed a neutral "stopped" for a router that had never come up.
     */
    private fun start() {
        try {
            startLocked()
        } catch (failure: Throwable) {
            val reason = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
            Log.e(TAG, "router failed to start: $reason", failure)
            runCatching { server?.stop() }
            server = null
            // Whatever a previous run left behind now describes a server that is not going to exist.
            discardEndpoint()
            recordFailure("router failed to start: $reason")
            stopSelf()
        }
    }

    private fun startLocked() {
        val loaded = RouterConfigLoader(applicationContext).load()
        if (!loaded.isUsable) {
            val reason = "routing table unusable: ${loaded.table.rejected}"
            Log.e(TAG, reason)
            recordFailure(reason)
            stopSelf()
            return
        }
        if (loaded.table.rejected.isNotEmpty()) Log.w(TAG, "validation dropped: ${loaded.table.rejected}")

        // Which table is actually running, and whether the user's own was the one that got ignored.
        // Someone editing a file that is not being read has no other way to find out.
        Log.i(TAG, "routing table from ${loaded.origin} (${RouterConfigLoader(applicationContext).userTableFile()})")
        loaded.rejected?.let { Log.w(TAG, "falling back to the bundled table: $it") }

        // Removed before the new server is bound, not after. Between binding and writing, the file
        // left by a previous run still names the *old* port - and a run that dies in that window
        // leaves it naming a port nothing ever listened on, which is the stale claim the status
        // check has to survive.
        discardEndpoint()
        runCatching { File(filesDir, "runtime/router-failure.txt").delete() }

        // The real store, re-read on every routing pass so a key the user just saved takes effect
        // without restarting the service. The placeholder that used to be here proved the loopback
        // and has no business surviving now that keys can actually be entered.
        val store = RouterKeys(applicationContext)
        if (!store.isAvailable()) {
            Log.e(TAG, "encrypted key store could not be opened; no provider can be called")
        }
        val keys: KeyStore = store
        val engine = RouterEngine(keys = { keys.snapshot() }, config = { loaded.table.config })

        // Fresh per run, 32 random bytes. It was a fixed string, which meant one captured token
        // opened every router that would ever run. The guest finds it in the endpoint file, and that
        // file is now owner-only, so the token is handed to something that can already see it and
        // nobody else.
        val token = newSessionToken()
        val running = RouterServer(LoggingGateway(engine) { message -> Log.i(TAG, message) }, token)
        val port = running.start()

        writeEndpoint(port, token)
        server = running
        Log.i(TAG, "router listening on http://127.0.0.1:$port (endpoint written to ${endpointFile(this)})")
        // The notification is the only always-visible surface of a process holding the user's keys,
        // and it said "starting" for the whole life of the service.
        runCatching { startForeground(NOTIFICATION_ID, notification("listening on 127.0.0.1:$port")) }
    }

    private fun writeEndpoint(
        port: Int,
        token: String,
    ) {
        val file = File(filesDir, "$ENDPOINT_DIRECTORY/$ENDPOINT_FILE")
        file.parentFile?.mkdirs()
        // Owner-only, before a byte is written. The file carries a live bearer token, and the guest
        // reads it as root - so anything wider than the owner is a window someone else could use.
        // A fresh file with the wrong mode is exactly the mistake, so it is set before the write.
        file.writeText(
            buildString {
                appendLine("ANTHROPIC_BASE_URL=http://127.0.0.1:$port")
                appendLine("ANTHROPIC_AUTH_TOKEN=$token")
                appendLine("ANTHROPIC_API_KEY=$token")
                appendLine("ROUTER_PID=${android.os.Process.myPid()}")
            },
        )
        runCatching {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
    }

    /** Why the last attempt did not work, so the screen can say it rather than showing nothing. */
    private fun recordFailure(reason: String) {
        runCatching { File(filesDir, "runtime/router-failure.txt").writeText(reason) }
    }

    private fun stop() {
        server?.stop()
        server = null
        // By path, every time - not through a field cached at start time.
        //
        // This is the stop button. The field this replaced was only assigned by a start() that got
        // all the way to writing the endpoint file, so whenever the service's own process had been
        // killed and restarted - which is what Android does to a backgrounded process, and what
        // onDestroy never runs for - there was nothing left holding that file. The server was gone,
        // the file survived, and status() went on reading it: the switch stayed on, the port stayed
        // on screen, and pressing Stop appeared to do nothing at all.
        discardEndpoint()
    }

    private fun discardEndpoint() {
        runCatching { endpointFile(this).delete() }
    }

    private fun notification(state: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Router")
            .setContentText("Loopback proxy for AI providers - $state")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    companion object {
        private const val TAG = "RouterService"
        private const val CHANNEL_ID = "router"

        /**
         * Unique across the app, not unique within this file.
         *
         * It shared 4201 with ScheduleExecutionService. Notification identity is per package, not per
         * process, and that is fine while the two use different channels - but when a schedule run
         * finished it called stopForeground(STOP_FOREGROUND_REMOVE), which deleted id 4201 and took
         * the router's own foreground notification with it while the router was still serving.
         */
        private const val NOTIFICATION_ID = 4202

        private const val LOOPBACK = "127.0.0.1"

        /** Long enough to not trip on a loaded device, short enough that the screen's poll stays cheap. */
        private const val LIVENESS_TIMEOUT_MILLIS = 250

        /** Inside the app's private storage, which is where the guest looks for runtime state. */
        private const val ENDPOINT_DIRECTORY = "runtime"
        private const val ENDPOINT_FILE = AgentRouterEndpoint.ENDPOINT_FILE_NAME
        private const val FAILURE_FILE = "router-failure.txt"

        /**
         * Where the router actually got to.
         *
         * The endpoint file is read, but it is a *claim*, not evidence: it is written once at start
         * and outlives the process that wrote it, so a router killed by the system kept advertising a
         * port nothing was listening on and the screen kept saying "running" over it. File presence
         * alone can only ever answer "did the service get as far as writing this", which is the
         * question that made a stale file look like a live server.
         *
         * So the file says which port to check, and a loopback connect says whether anything is
         * actually there. The connect is the cheapest proof that exists: it fails instantly on a
         * closed port and costs one loopback round trip when the router is healthy. Callers already
         * run this off the main thread, and the screen polls it, so the timeout is short.
         */
        fun status(context: Context): RouterStatus {
            val file = endpointFile(context)
            if (!file.isFile) return RouterStatus.NotRunning
            val values =
                runCatching {
                    file.readLines().mapNotNull { line ->
                        val value = line.substringAfter('=', "")
                        if (value.isBlank()) null else line.substringBefore('=') to value
                    }.toMap()
                }.getOrDefault(emptyMap())
            val url = values["ANTHROPIC_BASE_URL"] ?: return RouterStatus.NotRunning
            val port = url.substringAfterLast(':').trim().toIntOrNull() ?: return RouterStatus.NotRunning
            if (port !in 1..65535) return RouterStatus.NotRunning
            if (!isAccepting(port)) return RouterStatus.NotRunning
            return RouterStatus.Running(port)
        }

        /** Whether something is accepting connections on [port]. */
        private fun isAccepting(port: Int): Boolean =
            try {
                Socket().use { probe ->
                    probe.connect(InetSocketAddress(LOOPBACK, port), LIVENESS_TIMEOUT_MILLIS)
                    true
                }
            } catch (unreachable: IOException) {
                false
            }

        /**
         * Removes an endpoint file whose server is gone.
         *
         * For the stop path specifically. A service killed without onDestroy leaves the file behind
         * and nothing in its own process is left to delete it, so once [status] has established there
         * is no server on that port, the file is a claim about nothing and the UI caller removes it.
         * Without this the stop button polls, finds a stale file, and snaps back to "running".
         */
        fun discardStaleEndpoint(context: Context) {
            runCatching {
                val file = endpointFile(context)
                if (file.isFile) file.delete()
            }
        }

        /** A table that could not be loaded: the service refused to start, and the screen must say so. */
        fun lastFailure(context: Context): String? =
            runCatching { File(context.filesDir, "$ENDPOINT_DIRECTORY/$FAILURE_FILE").readText() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }

        fun start(context: Context) = context.startForegroundService(Intent(context, RouterService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, RouterService::class.java))

        /** Where an agent on this device finds the router's address. */
        fun endpointFile(context: Context): File = File(context.filesDir, "$ENDPOINT_DIRECTORY/$ENDPOINT_FILE")
    }
}

/**
 * Logs what passes through, and delegates.
 *
 * This exists because proving the loopback works means watching a request arrive, and without a line
 * in the log the only available evidence is an answer that came from somewhere.
 */
private class LoggingGateway(
    private val delegate: RouterGateway,
    private val log: (String) -> Unit,
) : RouterGateway {
    override fun isStreaming(rawRequest: String): Boolean = delegate.isStreaming(rawRequest)

    override fun handle(rawRequest: String): String? {
        log("POST /v1/messages (${rawRequest.length} bytes)")
        return delegate.handle(rawRequest)
    }

    override fun stream(
        rawRequest: String,
        onEvent: (String) -> Unit,
    ) {
        log("POST /v1/messages stream=true (${rawRequest.length} bytes)")
        delegate.stream(rawRequest, onEvent)
    }

    override fun listModels(): String = delegate.listModels()

    override fun countTokens(rawRequest: String): String = delegate.countTokens(rawRequest)

    override fun errorEvent(failure: Exception): String = delegate.errorEvent(failure)

    override fun onServerFailure(failure: Exception) {
        log("server failure: ${failure.message}")
        delegate.onServerFailure(failure)
    }
}

/** Whether the router is serving, and where. */
sealed interface RouterStatus {
    /** Not serving. The usual state when the user has not switched it on. */
    data object NotRunning : RouterStatus

    /** Serving on [port] at the given loopback address. */
    data class Running(val port: Int) : RouterStatus
}
