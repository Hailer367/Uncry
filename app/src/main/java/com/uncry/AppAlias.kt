package com.uncry

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Vanity launcher names. Android does not allow renaming an app at runtime,
 * so the manifest declares one activity-alias per name and exactly one alias
 * is enabled at a time via [apply] — the launcher then shows that alias's
 * label as the app name. Names are placeholders until the community
 * finalizes the list; keys must stay in sync with the Relayer allowlist.
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
        ctx.getSharedPreferences("uncry", Context.MODE_PRIVATE).edit()
            .putBoolean("app_hidden", hidden).apply()
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

    /** Enables the alias for [key] and disables the rest. Returns false on unknown key. */
    fun apply(ctx: Context, key: String): Boolean {
        val want = ALL.find { it.key == key } ?: return false
        return try {
            setAll(ctx, want.key)
            ctx.getSharedPreferences("uncry", Context.MODE_PRIVATE).edit()
                .putString("app_alias", want.key).apply()
            Log.i(TAG, "launcher name -> ${want.label}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "apply failed: ${e.message}")
            false
        }
    }

    /**
     * Hides every launcher alias — icon disappears from the launcher but the
     * app stays installed (visible in Settings) and keeps running so Teller
     * can bring it back with Visible.
     */
    fun hide(ctx: Context) {
        try {
            setAll(ctx, null)
        } catch (e: Exception) {
            Log.w(TAG, "hide failed: ${e.message}")
        }
        setHidden(ctx, true)
        Log.i(TAG, "launcher hidden")
    }

    /** Brings the current alias back to the launcher. */
    fun show(ctx: Context) {
        if (apply(ctx, current(ctx))) setHidden(ctx, false)
    }

    /** Idempotent — safe to call on every start / boot. Never unhides. */
    fun enforce(ctx: Context) {
        try {
            if (isHidden(ctx)) setAll(ctx, null) else apply(ctx, current(ctx))
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
