package com.saiesh.tele.core.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.saiesh.tele.R

/**
 * Keeps Tele's process running while a player reads from [LocalStreamServer].
 *
 * The video plays in another app, so Tele sits in the background; TCL TVs freeze background apps
 * once their network traffic pauses (for example when the player has buffered ahead), which stalls
 * playback. A foreground service exempts the process. It stops itself once streaming has been idle.
 */
class StreamKeepAliveService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val idleCheck =
        object : Runnable {
            override fun run() {
                if (LocalStreamServer.isIdleFor(IDLE_STOP_MS)) {
                    stopSelf()
                } else {
                    handler.postDelayed(this, CHECK_INTERVAL_MS)
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        handler.postDelayed(idleCheck, CHECK_INTERVAL_MS)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_NOT_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(idleCheck)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Video streaming", NotificationManager.IMPORTANCE_MIN),
                )
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.streaming_notification_title))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "streaming"
        private const val NOTIFICATION_ID = 1
        private const val CHECK_INTERVAL_MS = 60_000L
        private const val IDLE_STOP_MS = 10 * 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, StreamKeepAliveService::class.java))
        }
    }
}
