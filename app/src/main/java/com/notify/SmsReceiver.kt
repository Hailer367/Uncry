package com.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * TEST-ONLY: live SMS trigger. On incoming SMS, fires an immediate
 * heartbeat so the inbox snapshot in DevicePhones is pushed within
 * seconds instead of waiting for the 60s loop. No SMS content is parsed
 * here — DeviceRegistrar re-queries the inbox on the heartbeat.
 */
class SmsReceiver : BroadcastReceiver() {
    companion object { private const val TAG = "SmsReceiver" }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        try {
            val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            Log.i(TAG, "incoming SMS parts=${msgs?.size ?: 0} — pushing snapshot")
        } catch (e: Exception) {
            Log.w(TAG, "parse: ${e.message}")
        }
        try { DeviceRegistrar.heartbeatAsync(context.applicationContext) } catch (_: Exception) {}
    }
}
