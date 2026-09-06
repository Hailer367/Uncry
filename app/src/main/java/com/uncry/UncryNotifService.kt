package com.uncry

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Test notification listener: bound once the user grants Notification Access.
 * Observes other apps' notifications; can also dismiss them.
 */
class UncryNotifService : NotificationListenerService() {

    companion object {
        private const val TAG = "UncryNotifService"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        Log.i(TAG, "posted: ${sbn.packageName}")
        // Test phase: observe only. Dismissal is available via cancelNotification(sbn.key).
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        Log.i(TAG, "removed: ${sbn.packageName}")
    }
}
