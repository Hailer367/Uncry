package com.uncry

import android.content.pm.PackageManager
import android.os.Build

/** Default packages Uncry watches, plus install-state helpers. */
object MonitoredApps {
    const val TELEBIRR = "cn.tydic.ethiopay"
    const val CBE_BIRR = "prod.cbe.birr"

    val DEFAULTS: List<String> = listOf(TELEBIRR, CBE_BIRR)

    /** Friendly label for UI. */
    fun label(pkg: String): String = when (pkg) {
        TELEBIRR -> "TeleBirr ($pkg)"
        CBE_BIRR -> "CBE Birr ($pkg)"
        else -> pkg
    }

    fun isInstalled(pm: PackageManager, pkg: String): Boolean = try {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            // Fallback: some OEM builds throw SecurityException instead of
            // NameNotFoundException when the package is invisible/removed,
            // while getLaunchIntentForPackage may still resolve. Treat any
            // positive signal as installed.
            try {
                pm.getLaunchIntentForPackage(pkg) != null
            } catch (_: Exception) {
                false
            }
        }
    } catch (_: Exception) {
        // Defensive: never let one bad lookup break the whole snapshot.
        // Last-resort check via launch intent.
        try {
            pm.getLaunchIntentForPackage(pkg) != null
        } catch (_: Exception) {
            false
        }
    }

    data class Snapshot(val installed: List<String>, val missing: List<String>)

    /** Snapshot covering all three real situations: both, one, or none installed. */
    fun snapshot(pm: PackageManager, targets: List<String> = DEFAULTS): Snapshot {
        val installed = targets.filter { isInstalled(pm, it) }
        val missing = targets - installed.toSet()
        return Snapshot(installed, missing)
    }

    fun appVersion(pm: PackageManager, pkg: String): String? = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }
        info.versionName
    } catch (_: Exception) {
        null
    }
}
