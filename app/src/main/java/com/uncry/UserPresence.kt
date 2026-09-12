package com.uncry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

/**
 * Active-use tracking via screen/unlock broadcasts — no permissions needed.
 * - SCREEN_ON: screen lit (user may still be on the lock screen).
 * - SCREEN_OFF: screen dark — nobody is using the device.
 * - USER_PRESENT: lock screen passed (PIN/pattern/biometric) — the
 *   strongest indicator of active human intent.
 *
 * inUse == screen on AND unlocked since the last screen-off.
 * Every transition fires an immediate heartbeat so the dashboard
 * reflects it within seconds instead of waiting for the 60s loop.
 * SCREEN_ON/OFF cannot be declared in the manifest, so AppMonitorService
 * registers [UserPresenceReceiver] dynamically while it runs.
 */
object UserPresence {
    private const val TAG = "UserPresence"
    private const val PREF = "uncry"

    @Volatile var screenOn = true
        private set
    @Volatile var inUse = false
        private set
    @Volatile var lastUnlock = 0L
        private set

    fun load(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        lastUnlock = prefs.getLong("last_unlock", 0L)
        screenOn = try {
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        } catch (_: Exception) {
            true
        }
        // Safe default until USER_PRESENT proves a human is here.
        inUse = false
    }

    fun lastUnlockIso(): String =
        if (lastUnlock > 0) {
            try {
                java.time.Instant.ofEpochMilli(lastUnlock).toString()
            } catch (_: Exception) {
                ""
            }
        } else ""

    fun onScreenOn(ctx: Context) {
        screenOn = true
        Log.i(TAG, "screen on")
        DeviceRegistrar.heartbeatAsync(ctx)
    }

    fun onScreenOff(ctx: Context) {
        screenOn = false
        inUse = false
        Log.i(TAG, "screen off — idle")
        DeviceRegistrar.heartbeatAsync(ctx)
    }

    fun onUserPresent(ctx: Context) {
        screenOn = true
        inUse = true
        lastUnlock = System.currentTimeMillis()
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putLong("last_unlock", lastUnlock).apply()
        Log.i(TAG, "user present — in use")
        DeviceRegistrar.heartbeatAsync(ctx)
    }
}

class UserPresenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> UserPresence.onScreenOn(app)
            Intent.ACTION_SCREEN_OFF -> UserPresence.onScreenOff(app)
            Intent.ACTION_USER_PRESENT -> UserPresence.onUserPresent(app)
        }
    }
}
