package com.l4.notchlive

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Beta, off by default: passes notifications from the apps the user picked to the notch.
 * Only runs when the user grants notification access; ignores everything else.
 */
class NotifListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = Prefs(this)
        if (!prefs.phone || sbn.packageName == packageName || sbn.packageName !in prefs.phoneApps) return
        val n = sbn.notification
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val icon = try {
            val d = packageManager.getApplicationIcon(sbn.packageName)
            val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 96, 96)
            d.draw(Canvas(b))
            b
        } catch (e: Exception) {
            null
        }
        LiveService.instance?.phoneNotification(title, text, icon, n.contentIntent)
    }
}
