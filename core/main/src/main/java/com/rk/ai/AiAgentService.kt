package com.rk.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rk.activities.main.MainActivity
import com.rk.resources.drawables

/**
 * Lets the running agent (in [AiViewModel]) talk to its notification service across the stop action.
 * Same process, so a simple callback holder is enough.
 */
object AiAgentBus {
    /** Set by AiViewModel; invoked when the user taps Stop on the agent notification. */
    var onStop: (() -> Unit)? = null
}

/**
 * Foreground service shown while the AI agent is working. Keeps the agent turn alive if the user
 * leaves the app and shows a notification with a Stop action — the on-device equivalent of "the AI
 * is using the terminal; you can kill it". Started/stopped by [AiViewModel] around each turn.
 */
class AiAgentService : Service() {
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat(buildNotification("Working…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runCatching { AiAgentBus.onStop?.invoke() }
            stopSelfCompat()
            return START_NOT_STICKY
        }
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "Working…"
        startForegroundCompat(buildNotification(label))
        return START_NOT_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val content =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, AiAgentService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI agent")
            .setContentText(text)
            .setSmallIcon(drawables.bolt)
            .setContentIntent(content)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .addAction(NotificationCompat.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun stopSelfCompat() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AI agent", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the AI agent is working"
            }
        )
    }

    companion object {
        private const val NOTIFICATION_ID = 7423
        private const val CHANNEL_ID = "ai_agent_channel"
        private const val ACTION_STOP = "com.rk.ai.action.STOP"
        private const val EXTRA_LABEL = "label"

        fun start(context: Context, label: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AiAgentService::class.java).putExtra(EXTRA_LABEL, label),
            )
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AiAgentService::class.java)) }
        }
    }
}
