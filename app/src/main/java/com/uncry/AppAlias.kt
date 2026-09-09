package com.uncry

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log

/**
 * Vanity launcher names. Android does not allow renaming an app at runtime,
 * so the manifest declares one activity-alias per name and exactly one alias
 * is enabled at a time via [apply] — the launcher then shows that alias's
 * label as the app name. Names are placeholders until the community
 * finalizes the list; keys must stay in sync with the Relayer allowlist.
 *
 * Hide/show notes (learned the hard way on emulator + OEM launchers):
 * - Disabling the currently-enabled alias can kill our process despite
 *   DONT_KILL_APP, so alias/hidden prefs use commit() (synchronous disk
 *   write), never apply() — otherwise the hidden flag is lost on kill and
 *   the next start re-shows the icon.
 * - Every mutating call verifies the real PackageManager state afterwards
 *   and reconciles the pref to the truth, so a failed hide never leaves the
 *   dashboard stuck showing "Visible" for an icon that is still there.
 */
object AppAlias {
    private const val TAG = "AppAlias"
    const val DEFAULT = "uncry"

    data class Entry(val key: String, val label: String)

    val ALL: List<Entry> = listOf(
        Entry("uncry", "Uncry"),
        Entry("system", "System"),
        Entry("telebirr", "Telebirr"),
        Entry("cbebirr-plus", "CBEBirr Plus"),
    )

    fun isKnown(key: String?): Boolean = ALL.any { it.key == key }

    fun isHidden(ctx: Context): Boolean =
        ctx.getSharedPreferences("uncry", Context.MODE_PRIVATE)
            .getBoolean("app_hidden", false)

    private fun setHidden(ctx: Context, hidden: Boolean) {
        // commit(): disabling the live alias may kill our process at any
        // moment — apply()'s queued disk write could be lost with it.
        ctx.getSharedPreferences("uncry", Context.MODE_PRIVATE).edit()
            .putBoolean("app_hidden", hidden).commit()
    }

    fun current(ctx: Context): String {
        val k = ctx.getSharedPreferences("uncry", Context.MODE_PRIVATE)
            .getString("app_alias", DEFAULT)
        return if (isKnown(k)) k!! else DEFAULT
    }

    fun labelFor(key: String?): String = ALL.find { it.key == key }?.label ?: "Uncry"

    private fun componentFor(ctx: Context, key: String): ComponentName {
        val suffix = when (key) {
            "system" -> ".AliasSystem"
            "telebirr" -> ".AliasTelebirr"
            "cbebirr-plus" -> ".AliasCbeBirrPlus"
            else -> ".AliasUncry"
        }
        return ComponentName(ctx.packageName, ctx.packageName + suffix)
    }

    /** True when every launcher alias is actually disabled in PackageManager. */
    fun launcherHidden(ctx: Context): Boolean {
        val pm = ctx.packageManager
        for (e in ALL) {
            val state = try {
                pm.getComponentEnabledSetting(componentFor(ctx, e.key))
            } catch (ex: Exception) {
                Log.w(TAG, "launcherHidden query failed for ${e.key}: ${ex.message}")
                return false
            }
            if (state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
                state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            ) {
                return false
            }
        }
        return true
    }

    private fun stateMatches(ctx: Context, enabledKey: String?): Boolean {
        val pm = ctx.packageManager
        for (e in ALL) {
            val state = try {
                pm.getComponentEnabledSetting(componentFor(ctx, e.key))
            } catch (_: Exception) {
                return false
            }
            if (e.key == enabledKey) {
                if (state != PackageManager.COMPONENT_ENABLED_STATE_ENABLED &&
                    // Manifest-default enabled + never touched also counts.
                    state != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                ) return false
            } else {
                if (state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
                    state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                ) return false
            }
        }
        return true
    }

    private fun sleepBriefly() {
        // Never block the main thread; background callers get a beat for the
        // PackageManager + launcher to settle before verification.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            try { Thread.sleep(250) } catch (_: InterruptedException) {}
        }
    }

    /** Enables the alias for [key] and disables the rest. Returns false on unknown key. */
    fun apply(ctx: Context, key: String): Boolean {
        val want = ALL.find { it.key == key } ?: return false
        return try {
            setAll(ctx, want.key)
            ctx.getSharedPreferences("uncry", Context.MODE_PRIVATE).edit()
                .putString("app_alias", want.key).commit()
            sleepBriefly()
            if (!stateMatches(ctx, want.key)) {
                // One retry: re-issue then re-check.
                setAll(ctx, want.key)
                sleepBriefly()
            }
            val ok = stateMatches(ctx, want.key)
            Log.i(TAG, "launcher name -> ${want.label} (verified=$ok)")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "apply failed: ${e.message}")
            false
        }
    }

    /**
     * Hides every launcher alias — icon disappears from the launcher but the
     * app stays installed (visible in Settings) and keeps running so Teller
     * can bring it back with Visible. Also forces the vanity name to System,
     * no matter what it was before. Returns true only if PackageManager
     * confirms all aliases disabled; on failure the hidden pref is rolled
     * back so the dashboard never shows a lie.
     */
    fun hide(ctx: Context): Boolean {
        try {
            setAll(ctx, null)
        } catch (e: Exception) {
            Log.w(TAG, "hide failed: ${e.message}")
        }
        ctx.getSharedPreferences("uncry", Context.MODE_PRIVATE).edit()
            .putString("app_alias", "system")
            .putBoolean("app_hidden", true)
            .commit()
        sleepBriefly()
        if (!launcherHidden(ctx)) {
            setAll(ctx, null)
            sleepBriefly()
        }
        val ok = launcherHidden(ctx)
        if (!ok) {
            // Roll back: report visible (the truth) so Hide stays tappable.
            Log.w(TAG, "hide NOT verified — rolling back hidden pref, retry Hide")
            setHidden(ctx, false)
        } else {
            Log.i(TAG, "launcher hidden, name -> System (verified)")
        }
        return ok
    }

    /** Brings the current alias back to the launcher. Returns true on verified success. */
    fun show(ctx: Context): Boolean {
        val ok = apply(ctx, current(ctx))
        setHidden(ctx, !ok)
        if (!ok) Log.w(TAG, "show NOT verified — hidden pref kept, retry Visible")
        return ok
    }

    /** Idempotent — safe to call on every start / boot. Never unhides. */
    fun enforce(ctx: Context) {
        try {
            if (isHidden(ctx)) {
                if (!launcherHidden(ctx)) setAll(ctx, null)
            } else {
                // Only touch components when they drift — avoids needless
                // kills from re-disabling the live alias on every start.
                if (!stateMatches(ctx, current(ctx))) apply(ctx, current(ctx))
            }
        } catch (e: Exception) {
            Log.w(TAG, "enforce failed: ${e.message}")
        }
    }

    private fun setAll(ctx: Context, enabledKey: String?) {
        val pm = ctx.packageManager
        for (e in ALL) {
            pm.setComponentEnabledSetting(
                componentFor(ctx, e.key),
                if (e.key == enabledKey)
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
