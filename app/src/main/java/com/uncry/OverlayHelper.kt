package com.uncry

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Draw-over-other-apps helper for background Relay.
 * Easily removable: delete this file + its manifest permission + calls in
 * MainActivity/DeviceRegistrar and background Relay falls back to notification/BAL-blocked.
 */
object OverlayHelper {
    fun hasPermission(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(ctx)
        } else true
    }

    fun requestPermission(ctx: Context): Boolean {
        // Deep link directly to Uncry's overlay toggle (not the app list)
        // Try package: URI first (stock Android), fall back to generic list
        try {
            val direct = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.fromParts("package", ctx.packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Verify it resolves (some OEMs have no handler for package: URI)
            if (direct.resolveActivity(ctx.packageManager) != null) {
                ctx.startActivity(direct)
                return true
            }
        } catch (_: Exception) { }
        return try {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: Exception) { false }
    }

    fun needsApi(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
}
