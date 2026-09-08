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
            val pm = ctx.packageManager
            for (e in ALL) {
                pm.setComponentEnabledSetting(
                    componentFor(ctx, e.key),
                    if (e.key == want.key)
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            ctx.getSharedPreferences("uncry", Context.MODE_PRIVATE).edit()
                .putString("app_alias", want.key).apply()
            Log.i(TAG, "launcher name -> ${want.label}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "apply failed: ${e.message}")
            false
        }
    }

    /** Idempotent — safe to call on every start / boot. */
    fun enforce(ctx: Context) {
        apply(ctx, current(ctx))
    }
}
