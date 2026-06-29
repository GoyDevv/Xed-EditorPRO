package com.rk.runner

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
import com.rk.exec.ubuntuProcess
import com.rk.resources.drawables
import java.io.InputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that runs a project build/run **headlessly** inside the sandbox (via
 * [ubuntuProcess]) — no terminal screen is shown. Output is streamed into [RunOutputState] for the
 * editor's floating view, and a progress notification (with a Stop action) lets the user follow and
 * cancel the build from anywhere. Because it's a started foreground service, the build keeps running
 * even if the user leaves the app.
 */
class RunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    @Volatile private var proc: Process? = null
    @Volatile private var lastNotifyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat(buildNotification(RunOutputState.label.ifBlank { "Build" }, "Starting…", ongoing = true))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopProcess()
            RunOutputState.onFinished(-1)
            stopForegroundCompat(remove = true)
            stopSelf()
            return START_NOT_STICKY
        }

        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "Build"
        val workingDir = intent?.getStringExtra(EXTRA_WORKDIR)
        val args = intent?.getStringArrayListExtra(EXTRA_ARGS) ?: arrayListOf()
        val apkProjectDir = intent?.getStringExtra(EXTRA_APK_DIR)
        val syncProjectDir = intent?.getStringExtra(EXTRA_SYNC_DIR)

        startForegroundCompat(buildNotification(label, "Starting…", ongoing = true))
        RunOutputState.setStopper { stopProcess() }

        scope.launch {
            val sb = StringBuilder()
            val lock = Any()
            try {
                val p = ubuntuProcess(workingDir = workingDir, command = args.toList())
                proc = p

                fun pump(stream: InputStream) =
                    thread {
                        runCatching {
                            stream.bufferedReader().forEachLine { line ->
                                synchronized(lock) { sb.appendLine(line) }
                                RunOutputState.onOutput(sb.toString())
                                maybeUpdateNotification(label)
                            }
                        }
                    }

                val t1 = pump(p.inputStream)
                val t2 = pump(p.errorStream)
                val exit = p.waitFor()
                t1.join()
                t2.join()
                RunOutputState.onOutput(sb.toString())
                RunOutputState.onFinished(exit)
                if (exit == 0) {
                    // A successful gradle sync/build marks the project synced for this session.
                    if (syncProjectDir != null) ProjectRunner.markSynced(syncProjectDir)
                    // Android: install the freshly built APK.
                    if (apkProjectDir != null) ApkInstaller.install(applicationContext, apkProjectDir)
                }
                showResultNotification(label, exit)
            } catch (e: Exception) {
                synchronized(lock) { sb.appendLine("Error: ${e.message}") }
                RunOutputState.onOutput(sb.toString())
                RunOutputState.onFinished(-1)
                showResultNotification(label, -1)
            } finally {
                proc = null
                // DETACH so the final result notification stays visible after the service stops.
                stopForegroundCompat(remove = false)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun stopProcess() {
        runCatching { proc?.destroy() }
        proc = null
    }

    private fun maybeUpdateNotification(label: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < 700) return
        lastNotifyAt = now
        runCatching {
            notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification(label, RunOutputState.latestLine.ifBlank { "Running…" }, ongoing = true),
            )
        }
    }

    private fun showResultNotification(label: String, exit: Int) {
        val text = if (exit == 0) "Finished successfully" else "Failed (exit $exit)"
        runCatching {
            notificationManager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(label)
                    .setContentText(text)
                    .setSmallIcon(drawables.run)
                    .setContentIntent(contentIntent())
                    .setOnlyAlertOnce(true)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    private fun contentIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun buildNotification(label: String, text: String, ongoing: Boolean): Notification {
        val stopIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(this, RunService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(label)
            .setContentText(text)
            .setSmallIcon(drawables.run)
            .setContentIntent(contentIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .apply { if (ongoing) setProgress(0, 0, true) }
            .addAction(NotificationCompat.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat(remove: Boolean) {
        runCatching { stopForeground(if (remove) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH) }
    }

    private fun createChannel() {
        val channel =
            NotificationChannel(CHANNEL_ID, "Build / Run", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of background builds started by the Run button"
            }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val NOTIFICATION_ID = 7422
        private const val CHANNEL_ID = "run_build_channel"
        private const val ACTION_STOP = "com.rk.runner.action.STOP"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_WORKDIR = "workingDir"
        private const val EXTRA_ARGS = "args"
        private const val EXTRA_APK_DIR = "apkProjectDir"
        private const val EXTRA_SYNC_DIR = "syncProjectDir"

        /** Start a background build. [args] is the full sandbox command list for [ubuntuProcess]. */
        fun start(
            context: Context,
            label: String,
            workingDir: String?,
            args: ArrayList<String>,
            androidApkProjectDir: String? = null,
            syncProjectDir: String? = null,
        ) {
            val intent =
                Intent(context, RunService::class.java).apply {
                    putExtra(EXTRA_LABEL, label)
                    putExtra(EXTRA_WORKDIR, workingDir)
                    putStringArrayListExtra(EXTRA_ARGS, args)
                    putExtra(EXTRA_APK_DIR, androidApkProjectDir)
                    putExtra(EXTRA_SYNC_DIR, syncProjectDir)
                }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
