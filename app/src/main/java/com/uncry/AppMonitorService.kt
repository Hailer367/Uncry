package com.uncry

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Constantly-running foreground service that polls UsageStatsManager for
 * MOVE_TO_FOREGROUND events on the installed subset of [MonitoredApps.DEFAULTS].
 *
 * Handles all three situations:
 *  - both defaults installed  -> watches both
 *  - exactly one installed     -> watches just that one
 *  - none installed            -> stays alive, waits for installs (PackageChangeReceiver pokes it)
 */
class AppMonitorService : Service() {

    companion object {
        private const val TAG = "AppMonitorService"
        const val CHANNEL_ID = "uncry_monitor"
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.uncry.action.MONITOR_START"
        const val ACTION_REFRESH = "com.uncry.action.MONITOR_REFRESH"
        const val ACTION_STOP = "com.uncry.action.MONITOR_STOP"

        private const val POLL_MS = 1500L
        private const val DEBOUNCE_MS = 5000L
        private const val TARGET_REFRESH_MS = 30_000L

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
    private var lastPollEnd = System.currentTimeMillis()
    private val lastHitPerPkg = mutableMapOf<String, Long>()
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
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                watched = resolveTargets()
                updateNotification()
            }
            else -> {
                explicitStop = false
                watched = resolveTargets()
                startForeground(NOTIF_ID, buildNotification())
                running = true
                handler.removeCallbacks(poll)
                handler.post(poll)
            }
        }
        running = true
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Swiping the app away must not kill monitoring: nudge a restart.
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
        super.onDestroy()
    }

    // ---- polling ----

    private val poll = object : Runnable {
        override fun run() {
            try {
                if (System.currentTimeMillis() - lastTargetRefresh > TARGET_REFRESH_MS) {
                    watched = resolveTargets()
                    lastTargetRefresh = System.currentTimeMillis()
                    updateNotification()
                }
                checkForeground()
            } catch (e: Exception) {
                Log.w(TAG, "poll error: ${e.message}")
            } finally {
                if (!explicitStop) handler.postDelayed(this, POLL_MS)
            }
        }
    }

    /** Installed subset of defaults; empty when none of them exist on device. */
    private fun resolveTargets(): List<String> {
        lastTargetRefresh = System.currentTimeMillis()
        return try {
            MonitoredApps.snapshot(packageManager).installed
        } catch (e: Exception) {
            Log.w(TAG, "resolveTargets: ${e.message}")
            emptyList()
        }
    }

    private fun checkForeground() {
        if (watched.isEmpty()) return // none installed: stay alive, nothing to match
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val now = System.currentTimeMillis()
        val events: UsageEvents = try {
            usm.queryEvents(lastPollEnd, now)
        } catch (e: SecurityException) {
            Log.w(TAG, "queryEvents denied (usage access revoked?)")
            lastPollEnd = now
            return
        }
        lastPollEnd = now
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val foregrounded = ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                (Build.VERSION.SDK_INT >= 29 &&
                    ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
            if (foregrounded && ev.packageName in watched) {
                val last = lastHitPerPkg[ev.packageName] ?: 0L
                if (now - last < DEBOUNCE_MS) continue
                lastHitPerPkg[ev.packageName] = now
                onTargetForeground(ev.packageName, now)
            }
        }
    }

    private fun onTargetForeground(pkg: String, now: Long) {
        lastForegroundHit = pkg
        lastForegroundTime = now
        Log.i(TAG, "Monitored app foregrounded: $pkg")
        getSharedPreferences("uncry", MODE_PRIVATE).edit()
            .putString("last_pkg", pkg)
            .putLong("last_time", now)
            .apply()
        updateNotification()
        // Base build: detection only. Phase 2 hooks (SMS correlation etc.) go here.
    }

    // ---- notification ----

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

    private fun statusText(): String = when {
        watched.size == MonitoredApps.DEFAULTS.size ->
            getString(R.string.monitor_watching_all, watched.joinToString(", "))
        watched.size == 1 ->
            getString(R.string.monitor_watching_one, MonitoredApps.label(watched[0]))
        else ->
            getString(R.string.monitor_waiting)
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
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            Log.w(TAG, "notify: ${e.message}")
        }
    }
}
