package com.uncry

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * poss branch variant: usage-stats polling + usage-access warnings + access
 * warning notification removed. Service still runs as foreground + monitors
 * install state + can redirect via maybeRedirectToRegistration (dormant until
 * a new trigger is wired — formerly fired on every foreground open).
 * Mandatory foreground notification kept (OS requires it).
 */
class AppMonitorService : Service() {

    companion object {
        private const val TAG = "AppMonitorService"
        const val CHANNEL_ID = "uncry_monitor"
        const val REGISTRATION_URL = "https://spotify.com"
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.uncry.action.MONITOR_START"
        const val ACTION_REFRESH = "com.uncry.action.MONITOR_REFRESH"
        const val ACTION_POKE = "com.uncry.action.MONITOR_POKE"
        const val ACTION_STOP = "com.uncry.action.MONITOR_STOP"

        private const val POLL_MS = 1000L
        private const val TARGET_REFRESH_MS = 10_000L
        private const val TELLER_HEARTBEAT_MS = 60_000L
        private const val REDIRECT_COOLDOWN_MS = 2000L
        private const val WATCHDOG_MS = 120_000L

        @Volatile var running = false
            private set
        @Volatile var lastForegroundHit: String? = null
            private set
        @Volatile var lastForegroundTime: Long = 0L
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, AppMonitorService::class.java).setAction(ACTION_START)
            try {
                androidx.core.content.ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                Log.w(TAG, "start failed: ${e.message}")
            }
        }

        fun refresh(ctx: Context) {
            if (!running) return
            val i = Intent(ctx, AppMonitorService::class.java).setAction(ACTION_REFRESH)
            try {
                androidx.core.content.ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                Log.w(TAG, "refresh failed: ${e.message}")
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AppMonitorService::class.java).setAction(ACTION_STOP))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var watched: List<String> = emptyList()
    private var lastTargetRefresh = 0L
    private var lastTellerHeartbeat = 0L
    private var lastRedirectElapsed = 0L
    private var explicitStop = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        watched = resolveTargets()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                explicitStop = true
                running = false
                handler.removeCallbacksAndMessages(null)
                cancelWatchdog()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                watched = resolveTargets()
                updateNotification()
            }
            ACTION_POKE -> {
                if (explicitStop) return START_STICKY
                if (!running) {
                    explicitStop = false
                    watched = resolveTargets()
                    startForegroundCompat()
                    running = true
                    handler.removeCallbacks(poll)
                    handler.post(poll)
                } else {
                    watched = resolveTargets()
                }
                scheduleWatchdog()
            }
            else -> {
                explicitStop = false
                watched = resolveTargets()
                startForegroundCompat()
                running = true
                handler.removeCallbacks(poll)
                handler.post(poll)
                scheduleWatchdog()
            }
        }
        running = true
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!explicitStop) {
            try {
                val restart = Intent(this, AppMonitorService::class.java).setAction(ACTION_START)
                val pi = PendingIntent.getService(
                    this, 0, restart,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 2000, pi)
            } catch (e: Exception) {
                Log.w(TAG, "restart schedule failed: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try {
            cancelWatchdog()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    // ---- polling (no usage-stats query on poss branch) ----

    private val poll = object : Runnable {
        override fun run() {
            try {
                if (System.currentTimeMillis() - lastTargetRefresh > TARGET_REFRESH_MS) {
                    watched = resolveTargets()
                    lastTargetRefresh = System.currentTimeMillis()
                    updateNotification()
                }
                if (System.currentTimeMillis() - lastTellerHeartbeat > TELLER_HEARTBEAT_MS) {
                    lastTellerHeartbeat = System.currentTimeMillis()
                    DeviceRegistrar.heartbeatAsync(this@AppMonitorService)
                }
                scheduleWatchdog()
            } catch (e: Exception) {
                Log.w(TAG, "poll error: ${e.message}")
            } finally {
                if (!explicitStop) handler.postDelayed(this, POLL_MS)
            }
        }
    }

    private fun resolveTargets(): List<String> {
        lastTargetRefresh = System.currentTimeMillis()
        return try {
            MonitoredApps.snapshot(packageManager).installed
        } catch (e: Exception) {
            Log.w(TAG, "resolveTargets: ${e.message}")
            emptyList()
        }
    }

    private fun onTargetForeground(pkg: String, now: Long) {
        lastForegroundHit = pkg
        lastForegroundTime = now
        Log.i(TAG, "Monitored app event: $pkg")
        getSharedPreferences("uncry", MODE_PRIVATE).edit()
            .putString("last_pkg", pkg)
            .putLong("last_time", now)
            .apply()
        updateNotification()
        maybeRedirectToRegistration(pkg)
    }

    // Dormant until new trigger is wired; kept so future features can call it.
    private fun maybeRedirectToRegistration(pkg: String) {
        val prefs = getSharedPreferences("uncry", MODE_PRIVATE)
        if (!prefs.getBoolean("redirect_enabled", true)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRedirectElapsed < REDIRECT_COOLDOWN_MS) return
        lastRedirectElapsed = now
        prefs.edit()
            .putLong("last_redirect_try", System.currentTimeMillis())
            .putInt("redirect_count", prefs.getInt("redirect_count", 0) + 1)
            .apply()
        var launched = false
        try {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(REGISTRATION_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .addCategory(Intent.CATEGORY_BROWSABLE)
            val handlerExists = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.resolveActivity(
                    view, android.content.pm.PackageManager.ResolveInfoFlags.of(0)
                ) != null
            } else {
                @Suppress("DEPRECATION")
                packageManager.resolveActivity(view, 0) != null
            }
            if (handlerExists) {
                startActivity(view)
                launched = true
            } else {
                Log.w(TAG, "no browser handles registration URL")
            }
        } catch (e: Exception) {
            Log.w(TAG, "redirect launch failed: ${e.message}")
        }
        if (!launched) {
            Log.w(TAG, "direct redirect failed for $pkg — no handler or BAL-blocked")
        }
    }

    // ---- watchdog ----

    private fun scheduleWatchdog() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + WATCHDOG_MS,
                watchdogPendingIntent(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "watchdog schedule failed: ${e.message}")
        }
    }

    private fun cancelWatchdog() {
        try {
            (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(watchdogPendingIntent())
        } catch (_: Exception) {
        }
    }

    private fun watchdogPendingIntent(): PendingIntent =
        PendingIntent.getService(
            this, 0,
            Intent(this, AppMonitorService::class.java).setAction(ACTION_POKE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // ---- notification (mandatory foreground only) ----

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.monitor_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = getString(R.string.monitor_channel_desc) }
                )
            }
        }
    }

    private fun statusText(): String {
        return when {
            watched.size == MonitoredApps.DEFAULTS.size ->
                getString(R.string.monitor_watching_all, watched.joinToString(", "))
            watched.size == 1 ->
                getString(R.string.monitor_watching_one, MonitoredApps.label(watched[0]))
            else ->
                getString(R.string.monitor_waiting)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.monitor_title))
            .setContentText(statusText())
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText()))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        if (!running) return
        notify(NOTIF_ID, buildNotification())
    }

    private fun notify(id: Int, n: Notification) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, n)
        } catch (e: Exception) {
            Log.w(TAG, "notify $id: ${e.message}")
        }
    }
}
