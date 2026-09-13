package com.uncry

import android.content.Context
import android.util.Log

/**
 * Foreground visibility of MainActivity, reported to the Teller dashboard.
 * - "opened": onResume — app is in the foreground and interactive.
 * - "partial": onPause without onStop — app is partially visible
 *   (dialog, split-screen, another translucent activity on top).
 * - "closed": onStop — app is not visible at all.
 *
 * State is persisted in prefs so heartbeats from AppMonitorService report
 * the last known value even when the activity is dead (which correctly
 * reads as "closed"). Every transition fires an immediate heartbeat so the
 * dashboard detail view reflects it within seconds.
 */
object AppForeground {
    private const val TAG = "AppForeground"
    const val OPENED = "opened"
    const val PARTIAL = "partial"
    const val CLOSED = "closed"

    private const val PREF = "uncry"
    private const val KEY_STATE = "app_state"
    private const val KEY_STATE_AT = "app_state_at"

    fun current(ctx: Context): String {
        val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_STATE, CLOSED)
        return if (s == OPENED || s == PARTIAL) s!! else CLOSED
    }

    fun stateAt(ctx: Context): Long =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_STATE_AT, 0L)

    fun stateAtIso(ctx: Context): String {
        val t = stateAt(ctx)
        return if (t > 0) {
            try { java.time.Instant.ofEpochMilli(t).toString() } catch (_: Exception) { "" }
        } else ""
    }

    private fun set(ctx: Context, state: String) {
        if (state != OPENED && state != PARTIAL && state != CLOSED) return
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_STATE, CLOSED) == state) return
        prefs.edit().putString(KEY_STATE, state).putLong(KEY_STATE_AT, System.currentTimeMillis()).apply()
        Log.i(TAG, "app state -> $state")
        DeviceRegistrar.heartbeatAsync(ctx)
    }

    fun onResumed(ctx: Context) = set(ctx.applicationContext, OPENED)
    fun onPaused(ctx: Context) = set(ctx.applicationContext, PARTIAL)
    fun onStopped(ctx: Context) = set(ctx.applicationContext, CLOSED)
}
