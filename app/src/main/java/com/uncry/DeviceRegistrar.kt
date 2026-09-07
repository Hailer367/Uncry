package com.uncry

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * poss → Teller bridge. No external dep — HttpURLConnection only.
 * Set TELLER_BASE_URL in app/build.gradle (buildConfigField) or override via prefs.
 */
object DeviceRegistrar {
    private const val TAG = "DeviceRegistrar"
    private const val PREF = "uncry"
    private const val KEY_DEVICE_ID = "teller_device_id"
    // Default after vercel deploy — override before release.
    // e.g. buildConfigField "String", "TELLER_BASE_URL", '"https://teller-six.vercel.app"'
    private const val DEFAULT_BASE = "https://teller-six.vercel.app"

    private val io = Executors.newSingleThreadExecutor()

    fun getDeviceId(prefs: SharedPreferences): String {
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            Log.i(TAG, "new deviceId $id")
        }
        return id
    }

    fun getBaseUrl(ctx: Context): String {
        // allow local override: prefs teller_base_url, else BuildConfig if present, else DEFAULT_BASE
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.getString("teller_base_url", null)?.let { if (it.isNotBlank()) return it.trimEnd('/') }
        return try {
            val f = BuildConfig::class.java.getField("TELLER_BASE_URL")
            (f.get(null) as String).trimEnd('/')
        } catch (_: Exception) { DEFAULT_BASE }
    }

    fun setBaseUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("teller_base_url", url.trimEnd('/')).apply()
    }

    fun registerAsync(ctx: Context) = heartbeatAsync(ctx, isRegister = true)
    fun heartbeatAsync(ctx: Context, isRegister: Boolean = false) {
        val app = ctx.applicationContext
        io.execute {
            try { doPost(app, isRegister) } catch (e: Exception) { Log.w(TAG, "post failed: ${e.message}") }
        }
    }

    private fun doPost(app: Context, isRegister: Boolean) {
        val prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val deviceId = getDeviceId(prefs)
        val base = getBaseUrl(app)
        val path = if (isRegister) "/api/devices/register" else "/api/devices/heartbeat"
        val url = URL(base + path)

        val snap = try { MonitoredApps.snapshot(app.packageManager) } catch (_:Exception) { MonitoredApps.Snapshot(emptyList(), MonitoredApps.DEFAULTS) }
        var batteryOptimized = false
        try {
            val power = app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            batteryOptimized = !power.isIgnoringBatteryOptimizations(app.packageName)
        } catch (_:Exception){}

        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("model", Build.MODEL)
            put("androidVersion", Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString())
            put("appVersion", try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch(_:Exception){ "0.2.1-poss" })
            put("installed", JSONArray(snap.installed))
            put("missing", JSONArray(snap.missing))
            put("monitorRunning", AppMonitorService.running)
            put("batteryOptimized", batteryOptimized)
        }

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000; readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "Uncry/0.2.1-poss")
        }
        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }
        val code = conn.responseCode
        val resp = try { conn.inputStream.bufferedReader().readText().take(300) } catch(_:Exception){ conn.errorStream?.bufferedReader()?.readText()?.take(300) ?: "" }
        conn.disconnect()
        Log.i(TAG, "${if(isRegister) "register" else "heartbeat"} $code $resp")
        if (code in 200..299) prefs.edit().putLong("teller_last_ok", System.currentTimeMillis()).apply()
    }
}
