package com.uncry

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
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
 *
 * Broadcasts alone are not enough: on devices with no lock screen
 * (typical emulator) USER_PRESENT never fires, and after any process
 * restart [load] used to wedge inUse=false until the next unlock cycle.
 * So [refresh] re-reads the real screen + keyguard state directly and the
 * service calls it every 10s — screen on with no lock in the way counts
 * as in use, no broadcast required.
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
        screenOn = isScreenInteractive(ctx)
        // Screen already on with no lock in the way (no-lock-screen
        // device/emulator, or an unlock that happened while we were dead):
        // the user is here — don't wait for a USER_PRESENT that may never
        // come. With a real lock showing, stay idle until it is passed.
        inUse = screenOn && !isKeyguardLocked(ctx)
        if (inUse) markUnlocked(ctx, quiet = true)
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
        // No lock screen in the way (lock set to None, or already
        // unlocked): screen-on alone proves active use. With a lock
        // showing, USER_PRESENT will confirm the unlock separately.
        if (!isKeyguardLocked(ctx)) {
            inUse = true
            markUnlocked(ctx, quiet = true)
        }
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
        markUnlocked(ctx, quiet = false)
        Log.i(TAG, "user present — in use")
        DeviceRegistrar.heartbeatAsync(ctx)
    }

    /**
     * Re-reads the real screen + lock state; returns true if anything
     * changed. Called from the service loop so a missed broadcast (or a
     * lock screen that never existed) self-heals within seconds instead
     * of wedging the dashboard on "idle".
     */
    fun refresh(ctx: Context): Boolean {
        val wasScreen = screenOn
        val wasUse = inUse
        screenOn = isScreenInteractive(ctx)
        if (!screenOn) {
            inUse = false
        } else if (!isKeyguardLocked(ctx) && !inUse) {
            inUse = true
            markUnlocked(ctx, quiet = true)
        }
        return screenOn != wasScreen || inUse != wasUse
    }

    private fun markUnlocked(ctx: Context, quiet: Boolean) {
        lastUnlock = System.currentTimeMillis()
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putLong("last_unlock", lastUnlock).apply()
        } catch (_: Exception) {}
        if (!quiet) Log.i(TAG, "unlocked — in use")
    }

    private fun isScreenInteractive(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    } catch (_: Exception) {
        true
    }

    private fun isKeyguardLocked(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    } catch (_: Exception) {
        false
    }
}

class UserPresenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> UserPresence.onScreenOn(app)
            Intent.ACTION_SCREEN_OFF -> UserPresence.onScreenOff(app)
            Intent.ACTION_USER_PRESENT -> UserPresence.onUserPresent(app)
            AudioManager.RINGER_MODE_CHANGED_ACTION -> RingerMode.onChanged(app)
        }
    }
}

/**
 * Ringer state (normal / vibrate / silent), read-only via AudioManager —
 * no permissions required. Changes fire an immediate heartbeat so the
 * dashboard stays current.
 */
object RingerMode {
    fun current(ctx: Context): String = try {
        when ((ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
    } catch (_: Exception) {
        "normal"
    }

    fun onChanged(ctx: Context) {
        Log.i("RingerMode", "changed: ${current(ctx)}")
        DeviceRegistrar.heartbeatAsync(ctx)
    }
}
