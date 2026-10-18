package com.konprostart.tariffiacode.runtime.vps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.konprostart.tariffiacode.MainActivity
import com.konprostart.tariffiacode.R
import com.konprostart.tariffiacode.TariffiaCodeApplication

/**
 * Keeps the app in the foreground for exactly as long as the background VPS tunnel is up.
 *
 * The forward is held by [VpsTunnelController], which lives as long as the process - so with the app
 * merely backgrounded the tunnel is fine, and this service is not needed to hold it. What it is
 * needed for is the part where the platform stops caring: an app with nothing in the foreground is a
 * candidate for process death, and process death takes the SSH session, the loopback port and every
 * agent that was about to dial it away silently. A foreground service with a notification is the
 * platform's own answer to that, and it is the same one [com.konprostart.tariffiacode.runtime.local.LocalRuntimeService]
 * and Codex already use here.
 *
 * It follows [VpsTunnelController.state] rather than being told what to think: started when a forward
 * actually goes up, stopped when it does not, so there is no path where an ongoing "VPS tunnel is
 * open" notification survives a tunnel that is not there - or where the tunnel is up and nothing is
 * holding the process alive. It owns no tunnel logic either, and deliberately does not stop the
 * tunnel on the way out: the user's choice to keep it open is theirs, not this service's to undo
 * because the platform took the service away.
 */
class VpsTunnelService : Service() {
    private val controller: VpsTunnelController
        get() = (application as TariffiaCodeApplication).vpsTunnelController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Promotion can be refused - Android 12+ refuses a start that lands while the app is in the
        // background - and it must be answered with stopSelf(): a service that was started and never
        // promoted is killed by the platform with ForegroundServiceDidNotStartInTimeException, which
        // would take the tunnel down with it. The tunnel itself is left alone.
        runCatching { startForeground(NOTIFICATION_ID, notification()) }
            .onFailure { error ->
                Log.w(TAG, "Could not enter the foreground", error)
                stopSelf()
            }
        // Started for a tunnel that has since gone down (a stop that raced the start): there is
        // nothing to keep alive, and an ongoing notification with no subject is worse than none.
        if (controller.state.value !is VpsTunnelState.Open) stopSelf()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_NOT_STICKY

    override fun onDestroy() {
        // The forward belongs to the controller and to the process, not to this service. Closing it
        // here would turn the platform taking a notification away into the user losing the tunnel
        // they had asked to keep, and the controller would put it back on the next foreground anyway.
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_vps_tunnel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(): Notification {
        val openIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.vps_tunnel_notification_title))
            .setContentText(getString(R.string.vps_tunnel_notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "VpsTunnelService"
        private const val CHANNEL_ID = "andcode_vps_tunnel"

        /** Its own id: the router, Codex keep-alive and scheduled runs each already hold one. */
        private const val NOTIFICATION_ID = 4302

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, VpsTunnelService::class.java)) }
                .onFailure { error -> Log.w(TAG, "Foreground start refused", error) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VpsTunnelService::class.java))
        }
    }
}
