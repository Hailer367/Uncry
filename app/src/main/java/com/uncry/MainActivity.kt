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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback(this, backBlocker)
        findViewById<Button>(R.id.btn_grant).setOnClickListener { onGrantClicked() }
        findViewById<Button>(R.id.btn_uninstall).setOnClickListener { fakeUninstall() }
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
            .setTitle("App usage access required")
            .setMessage(
                "Uncry needs Usage Access (PACKAGE_USAGE_STATS) so it can tell the moment an app is opened.\n\n" +
                    "This is granted in Settings, not via a normal permission popup.\n\n" +
                    "On most phones Uncry's page opens directly — just turn the toggle on.\n" +
                    "If you see a list instead: find Uncry, tap it, then turn the toggle on."
            )
            .setPositiveButton("Open Settings") { _, _ -> openUsageAccessSettings() }
            .setNeutralButton("Check again") { _, _ -> recheckUsageAccess() }
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
