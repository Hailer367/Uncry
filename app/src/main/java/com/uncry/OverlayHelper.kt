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

    fun requestPermission(ctx: Context): Boolean = try {
        val i = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${ctx.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    } catch (_: Exception) { false }

    fun needsApi(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
}
