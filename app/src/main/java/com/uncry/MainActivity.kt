package com.uncry

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Minimal dev screen: a centered countdown (6h36m loop) + one explainer
 * line. No buttons, no status info. All background wiring (monitor
 * service, Teller register/heartbeat, alias enforcement) is unchanged —
 * remote control from the Teller dashboard still works as before.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // 6 hours 36 minutes, in milliseconds. Restarts on finish.
        private const val CYCLE_MS = (6 * 3600L + 36 * 60L) * 1000L
    }

    private var timer: CountDownTimer? = null
    private var batteryDialog: AlertDialog? = null

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Never auto-show: opening this screen while hidden must NOT bring
        // the icon back. enforce() only re-asserts the hidden state.
        AppAlias.enforce(this)
        AppMonitorService.start(this)
        DeviceRegistrar.registerAsync(this)
        startTimer()
        maybePromptBatteryExemption()
        requestNotifPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        DeviceRegistrar.heartbeatAsync(this)
    }

    override fun onDestroy() {
        timer?.cancel()
        timer = null
        batteryDialog?.dismiss()
        batteryDialog = null
        super.onDestroy()
    }

    private fun startTimer() {
        timer?.cancel()
        timer = object : CountDownTimer(CYCLE_MS, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                render(millisUntilFinished)
            }

            override fun onFinish() {
                // Loop: restart the full 6h36m cycle.
                startTimer()
            }
        }.start()
        render(CYCLE_MS)
    }

    private fun render(millis: Long) {
        val totalSec = millis / 1000L
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        findViewById<TextView>(R.id.countdown_timer).text =
            String.format(java.util.Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
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
            }
            .setCancelable(false).show()
    }
}
