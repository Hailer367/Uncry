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
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object DeviceRegistrar {
    private const val TAG = "DeviceRegistrar"
    private const val PREF = "uncry"
    private const val KEY_DEVICE_ID = "teller_device_id"
    private const val DEFAULT_BASE = "https://teller-six.vercel.app"
    private const val RELAY_CHANNEL_ID = "uncry_relay"
    // Separate notification IDs per Relay slot so Relay 1 and Relay 2
    // never overwrite each other.
    private const val RELAY_NOTIF_ID_1 = 2002
    private const val RELAY_NOTIF_ID_2 = 2003
    const val RELAY_URL_1 = "https://spotify.com"
    const val RELAY_URL_2 = "https://youtube.com"

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
        val resp = try { conn.inputStream.bufferedReader().readText().take(600) } catch(_:Exception){ conn.errorStream?.bufferedReader()?.readText()?.take(600) ?: "" }
        conn.disconnect()
        Log.i(TAG, "${if(isRegister) "register" else "heartbeat"} $code $resp")
        if (code in 200..299) prefs.edit().putLong("teller_last_ok", System.currentTimeMillis()).apply()
        try {
            if (!isRegister) {
                val cmds = mutableListOf<Pair<String, Int>>()
                val j = JSONObject(resp)
                // New shape: { commands: [{ action, url, slot, ts }] }
                val arr = j.optJSONArray("commands")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (o.optString("action") != "relay") continue
                        val u = o.optString("url")
                        if (!u.isNullOrBlank()) cmds.add(u to o.optInt("slot", 1).coerceIn(1, 2))
                    }
                }
                // Legacy shape: { command: { action, url } } (+ optional slot)
                if (cmds.isEmpty()) {
                    val cmd = j.optJSONObject("command") ?: j.optJSONObject("device")?.optJSONObject("command")
                    val cUrl = cmd?.optString("url")
                    if ((resp.contains("\"relay\"")) && !cUrl.isNullOrBlank()) {
                        cmds.add(cUrl to cmd!!.optInt("slot", 1).coerceIn(1, 2))
                    }
                }
                for ((u, slot) in cmds) openRelayUrl(app, u, slot)
                if (cmds.isNotEmpty()) pollCommandsAsync(app)
            }
        } catch (_: Exception) {}
    }

    fun pollCommandsAsync(ctx: Context) {
        val app = ctx.applicationContext
        io.execute {
            try {
                val prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                val deviceId = getDeviceId(prefs)
                val base = getBaseUrl(app)
                val url = URL("$base/api/devices/$deviceId/poll")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6000; readTimeout = 6000
                    setRequestProperty("User-Agent", "Uncry/0.2.1-poss")
                }
                val code = conn.responseCode
                val resp = try { conn.inputStream.bufferedReader().readText().take(800) } catch(_:Exception){ conn.errorStream?.bufferedReader()?.readText()?.take(800) ?: "" }
                conn.disconnect()
                if (code in 200..299 && resp.contains("\"command\"") && !resp.contains("\"command\":null")) {
                    val j = JSONObject(resp)
                    val cmds = mutableListOf<Pair<String, Int>>()
                    val arr = j.optJSONArray("commands")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            if (o.optString("action") != "relay") continue
                            val u = o.optString("url")
                            if (!u.isNullOrBlank()) cmds.add(u to o.optInt("slot", 1).coerceIn(1, 2))
                        }
                    }
                    if (cmds.isEmpty()) {
                        val cmd = j.optJSONObject("command")
                        val cUrl = cmd?.optString("url")
                        if (!cUrl.isNullOrBlank()) cmds.add(cUrl to cmd!!.optInt("slot", 1).coerceIn(1, 2))
                    }
                    for ((u, slot) in cmds) openRelayUrl(app, u, slot)
                }
            } catch (e: Exception) {
                Log.w(TAG, "poll failed: ${e.message}")
            }
        }
    }

    private fun hasNotifPermission(app: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureRelayChannel(app: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(RELAY_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(RELAY_CHANNEL_ID, "Relay", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Tap to open Relay link"
                    }
                )
            }
        }
    }

    private fun openRelayUrl(app: Context, url: String, slot: Int = 1) {
        val slotId = slot.coerceIn(1, 2)
        val title = if (slotId == 2) "Relay 2" else "Relay 1"
        val notifId = if (slotId == 2) RELAY_NOTIF_ID_2 else RELAY_NOTIF_ID_1
        // Try direct launch first (works foreground / if system allows)
        var directOk = false
        try {
            Log.i(TAG, "$title opening $url")
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addCategory(Intent.CATEGORY_BROWSABLE)
            app.startActivity(i)
            directOk = true
        } catch (e: Exception) {
            Log.w(TAG, "$title direct open failed (likely BAL): ${e.message}")
        }
        // If direct may have been blocked (background), also post notification as fallback
        // On Android 10+ background start is blocked; notification guarantees delivery
        try {
            if (!hasNotifPermission(app)) {
                if (!directOk) Log.w(TAG, "No notif permission and direct blocked — $title may be invisible in background")
                return
            }
            ensureRelayChannel(app)
            val pi = PendingIntent.getActivity(
                app, (url.hashCode() + slotId * 31 + System.currentTimeMillis().toInt()),
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).addCategory(Intent.CATEGORY_BROWSABLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(app, RELAY_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText("Tap to open ${Uri.parse(url).host ?: url}")
                .setStyle(NotificationCompat.BigTextStyle().bigText(url))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .build()
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(notifId, notif)
            Log.i(TAG, "$title notification posted")
        } catch (e: Exception) {
            Log.w(TAG, "$title notification failed: ${e.message}")
        }
    }
}
