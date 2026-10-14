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
    private var endpointFile: File? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("starting"))
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
        if (server != null) return START_STICKY
        start()
        return START_STICKY
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun start() {
        val loaded = RouterConfigLoader(applicationContext).load()
        if (!loaded.isUsable) {
            Log.e(TAG, "routing table unusable: ${loaded.table.rejected}")
            stopSelf()
            return
        }
        if (loaded.table.rejected.isNotEmpty()) Log.w(TAG, "validation dropped: ${loaded.table.rejected}")

        // Which table is actually running, and whether the user's own was the one that got ignored.
        // Someone editing a file that is not being read has no other way to find out.
        Log.i(TAG, "routing table from ${loaded.origin} (${RouterConfigLoader(applicationContext).userTableFile()})")
        loaded.rejected?.let { Log.w(TAG, "falling back to the bundled table: $it") }

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

        val file = writeEndpoint(port, token)
        endpointFile = file
        server = running
        Log.i(TAG, "router listening on http://127.0.0.1:$port (endpoint written to $file)")
    }

    private fun writeEndpoint(
        port: Int,
        token: String,
    ): File {
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
        return file
    }

    private fun stop() {
        server?.stop()
        server = null
        endpointFile?.delete()
        endpointFile = null
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
        private const val NOTIFICATION_ID = 4201

        /** Inside the app's private storage, which is where the guest looks for runtime state. */
        private const val ENDPOINT_DIRECTORY = "runtime"
        private const val ENDPOINT_FILE = RouterEndpoint.ENDPOINT_FILE_NAME

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
