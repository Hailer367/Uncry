package com.uncry

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Test accessibility service: declared so Uncry can request Accessibility
 * access in the permission flow. No event handling yet.
 */
class UncryAccessService : AccessibilityService() {

    companion object {
        private const val TAG = "UncryAccessService"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Test phase: detection only, no handling yet.
    }

    override fun onInterrupt() {
        Log.i(TAG, "interrupted")
    }
}
