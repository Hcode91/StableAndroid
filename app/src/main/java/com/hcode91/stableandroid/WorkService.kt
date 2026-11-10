package com.hcode91.stableandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground service that keeps the process (and CPU) alive while a model is
 * loading or images are generating, so leaving the app doesn't kill the work.
 * The work itself still runs on MainActivity's threads in the same process.
 */
class WorkService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Working…"
        ensureChannels(this)
        startForeground(ID_PROGRESS, progressNotification(this, text, 0, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StableAndroid:work").apply {
                    setReferenceCounted(false)
                    acquire(3 * 60 * 60 * 1000L)   // safety limit: 3 hours
                }
        }
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        try { wakeLock?.release() } catch (_: Throwable) { }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CH_PROGRESS = "progress"
        private const val CH_DONE = "done"
        private const val ID_PROGRESS = 1
        private const val ID_DONE = 2
        private const val EXTRA_TEXT = "text"

        @Volatile var running = false
            private set

        fun start(ctx: Context, text: String) {
            val app = ctx.applicationContext
            try {
                app.getSystemService(NotificationManager::class.java).cancel(ID_DONE)
                app.startForegroundService(Intent(app, WorkService::class.java).putExtra(EXTRA_TEXT, text))
            } catch (_: Throwable) {
                // Not allowed right now (e.g. app already in background): work continues without it.
            }
        }

        fun update(ctx: Context, text: String, step: Int, total: Int) {
            if (!running) return
            try {
                ctx.getSystemService(NotificationManager::class.java)
                    .notify(ID_PROGRESS, progressNotification(ctx, text, step, total))
            } catch (_: Throwable) { }
        }

        /** Stops the service; with [announce] also posts a "finished" notification. */
        fun finish(ctx: Context, text: String, announce: Boolean) {
            val app = ctx.applicationContext
            try { app.stopService(Intent(app, WorkService::class.java)) } catch (_: Throwable) { }
            if (!announce) return
            try {
                ensureChannels(app)
                val n = Notification.Builder(app, CH_DONE)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("StableAndroid")
                    .setContentText(text)
                    .setContentIntent(openApp(app))
                    .setAutoCancel(true)
                    .build()
                app.getSystemService(NotificationManager::class.java).notify(ID_DONE, n)
            } catch (_: Throwable) { }
        }

        private fun ensureChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_PROGRESS, "Generation progress", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(
                NotificationChannel(CH_DONE, "Generation finished", NotificationManager.IMPORTANCE_DEFAULT))
        }

        private fun openApp(ctx: Context): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun progressNotification(ctx: Context, text: String, step: Int, total: Int): Notification =
            Notification.Builder(ctx, CH_PROGRESS)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("StableAndroid is working")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(total, step, total <= 0)
                .setContentIntent(openApp(ctx))
                .build()
    }
}
