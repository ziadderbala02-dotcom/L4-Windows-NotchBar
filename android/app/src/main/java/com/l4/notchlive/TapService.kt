package com.l4.notchlive

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Optional. Its only job is to host the notch window above the status bar, so a tap on it
 * opens the match (a normal overlay sits under the status bar, which swallows taps).
 * It reads nothing on screen.
 */
class TapService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        LiveService.instance?.hostChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        LiveService.instance?.hostChanged()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        LiveService.instance?.hostChanged()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: TapService? = null
    }
}
