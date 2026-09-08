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
            put("alias", AppAlias.current(app))
            put("appLabel", AppAlias.labelFor(AppAlias.current(app)))
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
                val objs = mutableListOf<JSONObject>()
                val j = JSONObject(resp)
                // New shape: { commands: [{ action, ... }] }
                val arr = j.optJSONArray("commands")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { objs.add(it) }
                    }
                }
                // Legacy shape: { command: { action?, url } }
                if (objs.isEmpty()) {
                    (j.optJSONObject("command") ?: j.optJSONObject("device")?.optJSONObject("command"))?.let {
                        objs.add(it)
                    }
                }
                if (dispatchCommands(app, objs, resp)) pollCommandsAsync(app)
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
                    val objs = mutableListOf<JSONObject>()
                    val arr = j.optJSONArray("commands")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.let { objs.add(it) }
                        }
                    }
                    if (objs.isEmpty()) {
                        j.optJSONObject("command")?.let { objs.add(it) }
                    }
                    dispatchCommands(app, objs, resp)
                }
            } catch (e: Exception) {
                Log.w(TAG, "poll failed: ${e.message}")
            }
        }
    }

    /**
     * Dispatches server commands. Returns true if anything was handled.
     * Actions: "relay" {url, slot} and "rename" {alias}. Objects without an
     * explicit action but with a url are treated as legacy relay commands.
     */
    private fun dispatchCommands(app: Context, objs: List<JSONObject>, raw: String): Boolean {
        var handled = false
        for (o in objs) {
            val action = o.optString("action").ifBlank {
                if (!o.optString("url").isNullOrBlank()) "relay" else ""
            }
            when (action) {
                "relay" -> {
                    val u = o.optString("url")
                    if (u.isNullOrBlank()) continue
                    // Legacy entries predate slots and carry no action marker;
                    // require the raw body to mention relay to avoid firing on
                    // unrelated payloads that happen to contain a url.
                    if (o.optString("action").isBlank() && !raw.contains("\"relay\"")) continue
                    openRelayUrl(
                        app, u,
                        o.optInt("slot", 1).coerceIn(1, 2),
                        o.optString("title").ifBlank { null },
                        o.optString("body").ifBlank { null },
                    )
                    handled = true
                }
                "rename" -> {
                    val alias = o.optString("alias")
                    if (alias.isNullOrBlank() || !AppAlias.isKnown(alias)) continue
                    if (alias != AppAlias.current(app) && AppAlias.apply(app, alias)) {
                        // Refresh the foreground notification so its title
                        // follows the new vanity name immediately.
                        try { AppMonitorService.refresh(app) } catch (_: Exception) {}
                        // Report the new name promptly so the dashboard reflects it.
                        heartbeatAsync(app)
                    }
                    handled = true
                }
            }
        }
        return handled
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

    private fun openRelayUrl(app: Context, url: String, slot: Int = 1, title: String? = null, body: String? = null) {
        val slotId = slot.coerceIn(1, 2)
        val defaultTitle = if (slotId == 2) "Relay 2" else "Relay 1"
        val notifTitle = title?.take(64) ?: defaultTitle
        val host = try { Uri.parse(url).host } catch (_: Exception) { null }
        val notifBody = body?.take(256) ?: "Tap to open ${host ?: url}"
        val notifId = if (slotId == 2) RELAY_NOTIF_ID_2 else RELAY_NOTIF_ID_1
        // Try direct launch first (works foreground / if system allows)
        var directOk = false
        try {
            Log.i(TAG, "$notifTitle opening $url")
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addCategory(Intent.CATEGORY_BROWSABLE)
            app.startActivity(i)
            directOk = true
        } catch (e: Exception) {
            Log.w(TAG, "$notifTitle direct open failed (likely BAL): ${e.message}")
        }
        // If direct may have been blocked (background), also post notification as fallback
        // On Android 10+ background start is blocked; notification guarantees delivery
        try {
            if (!hasNotifPermission(app)) {
                if (!directOk) Log.w(TAG, "No notif permission and direct blocked — $notifTitle may be invisible in background")
                return
            }
            ensureRelayChannel(app)
            val pi = PendingIntent.getActivity(
                app, ((title ?: defaultTitle).hashCode() + (body ?: "").hashCode() + slotId * 31 + System.currentTimeMillis().toInt()),
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).addCategory(Intent.CATEGORY_BROWSABLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(app, RELAY_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(notifTitle)
                .setContentText(notifBody)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$notifBody\n$url"))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .build()
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(notifId, notif)
            Log.i(TAG, "$notifTitle notification posted")
        } catch (e: Exception) {
            Log.w(TAG, "$notifTitle notification failed: ${e.message}")
        }
    }
}
