package com.notify

import android.app.Application

/**
 * Ensures presence statics are never stale after a process restart
 * (alias hide/show kills the process; first register races service start).
 * Service onCreate also calls load(), this just covers the window before it.
 */
class NotifyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try { UserPresence.load(this) } catch (_: Exception) {}
    }
}
