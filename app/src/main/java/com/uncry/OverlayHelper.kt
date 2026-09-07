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
        // Exact snippet from AOSP docs / StackOverflow that works on API 34 emulator:
        // Intent(ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
        // No resolveActivity gate, no fromParts — Uri.parse package:
        try {
            val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
            // Use Activity context directly (no NEW_TASK needed when called from MainActivity)
            if (ctx is android.app.Activity) ctx.startActivityForResult(i, 2084)
            else {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            }
            return true
        } catch (_: Exception) { }
        return try {
            val f = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (ctx is android.app.Activity) {
                ctx.startActivityForResult(f, 2084)
            } else ctx.startActivity(f)
            true
        } catch (_: Exception) { false }
    }

    fun needsApi(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
}
