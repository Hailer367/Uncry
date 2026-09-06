package com.uncry

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
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
        const val ACTION_CHANNEL_ID = "uncry_action"
        /** Placeholder until the client supplies the real registration site. */
        const val REGISTRATION_URL = "https://spotify.com"
        const val NOTIF_ID = 1001
        const val ACCESS_WARN_NOTIF_ID = 1003
        const val ACTION_START = "com.uncry.action.MONITOR_START"
        const val ACTION_REFRESH = "com.uncry.action.MONITOR_REFRESH"
        const val ACTION_POKE = "com.uncry.action.MONITOR_POKE"
        const val ACTION_STOP = "com.uncry.action.MONITOR_STOP"

        private const val POLL_MS = 1000L
        private const val TARGET_REFRESH_MS = 10_000L
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
    private var lastPollEnd = System.currentTimeMillis()
    private var lastRedirectElapsed = 0L
    private var explicitStop = false
    private var usageLostWarned = false

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
                verifyUsageAccess()
                checkForeground()
                updateNotification()
            }
            ACTION_POKE -> {
                // Watchdog alarm (fires even in Doze): run one immediate check
                // over the whole missed window, then re-arm. Revives polling
                // if the process was recreated without an explicit stop.
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
                    verifyUsageAccess()
                    checkForeground()
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
        try {
            cancelWatchdog()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    // ---- polling ----

    private val poll = object : Runnable {
        override fun run() {
            try {
                if (System.currentTimeMillis() - lastTargetRefresh > TARGET_REFRESH_MS) {
                    watched = resolveTargets()
                    lastTargetRefresh = System.currentTimeMillis()
                    verifyUsageAccess()
                    updateNotification()
                }
                checkForeground()
                scheduleWatchdog()
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
            verifyUsageAccess()
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
                // No debounce here on purpose: every return to the app must
                // bounce straight back to the site. The redirect cooldown is
                // the only throttle, so this can't intent-spam.
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
        maybeRedirectToRegistration(pkg)
    }

    // ---- redirect: layered so no single OS behavior silently disables it ----

    /**
     * Bounces the user to the registration site in their default browser the
     * moment a monitored app opens, so the app itself stays unusable until
     * registration completes. Constant for now; the future website pass flips
     * the "redirect_enabled" flag off and this stops on its own.
     *
     * Direct ACTION_VIEW start only (blocked without warning by Android 10+
     * background-activity-start rules, or missing browser). Telemetry
     * (redirect_count / last_redirect_try in prefs + logcat) keeps a dead
     * redirect diagnosable instead of invisible.
     */
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
                    view, PackageManager.ResolveInfoFlags.of(0)
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
            Log.w(TAG, "direct redirect failed for $pkg — no fallback, launch was BAL-blocked or has no handler")
        }
    }

    // ---- usage-access loss: a blind monitor must say so loudly ----

    private fun hasUsageAccess(): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Without usage access every query returns nothing and the redirect
     * silently stops working. Surface that state in the persistent
     * notification plus a one-shot tappable warning (user tap is
     * BAL-exempt, so it always opens Uncry for re-grant).
     */
    private fun verifyUsageAccess() {
        if (hasUsageAccess()) {
            if (usageLostWarned) {
                usageLostWarned = false
                cancelNotification(ACCESS_WARN_NOTIF_ID)
                updateNotification()
            }
            return
        }
        updateNotification()
        if (usageLostWarned) return
        usageLostWarned = true
        Log.w(TAG, "usage access lost — monitoring blind, warning posted")
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, actionChannel())
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(getString(R.string.access_warn_title))
            .setContentText(getString(R.string.access_warn_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        notify(ACCESS_WARN_NOTIF_ID, n)
    }

    // ---- watchdog: fires in Doze, covers handler delays ----

    private fun scheduleWatchdog() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            // Inexact allow-while-idle: no extra permission needed, fires even
            // in Doze. The POKE handler queries the full missed window, so a
            // delayed handler never means a missed foreground event.
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

    // ---- notification ----

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
        // Eagerly create the high-importance channel too, so the first
        // redirect/warning never races channel creation.
        actionChannel()
    }

    /** High-importance channel for redirect + access warnings. Created lazily. */
    private fun actionChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(ACTION_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        ACTION_CHANNEL_ID,
                        getString(R.string.action_channel_name),
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply { description = getString(R.string.action_channel_desc) }
                )
            }
        }
        return ACTION_CHANNEL_ID
    }

    private fun statusText(): String {
        if (!hasUsageAccess()) {
            return getString(R.string.monitor_paused_access)
        }
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

    private fun cancelNotification(id: Int) {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id)
        } catch (_: Exception) {
        }
    }
}
