package com.uncry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Auto-starter: boots monitoring after startup / update, refreshes it when
 * packages change (covers the one-installed / none-installed transitions).
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "autostart on ${intent.action}")
                // Note: on Android 12+ the OS may block background FGS
                // starts here; AppMonitorService.start() already swallows
                // that and MainActivity re-starts the service on next open.
                AppMonitorService.start(app)
            }
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED -> {
                val pkg = intent.data?.schemeSpecificPart
                if (pkg != null && pkg in MonitoredApps.DEFAULTS) {
                    Log.i(TAG, "monitored package changed: ${intent.action} $pkg")
                    // ACTION_PACKAGE_ADDED with REPLACING extra fires during
                    // updates too — still refresh so version/availability
                    // never goes stale ("not installed" after install).
                    try {
                        if (AppMonitorService.running) AppMonitorService.refresh(app)
                        else AppMonitorService.start(app)
                    } catch (e: Exception) {
                        Log.w(TAG, "refresh after package change failed: ${e.message}")
                    }
                }
            }
        }
    }
}
