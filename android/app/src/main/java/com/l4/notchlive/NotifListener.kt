package com.l4.notchlive

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.File

/**
 * Only runs when the user grants notification access. (Phone notifications in the notch were
 * dropped in 1.5.1: not practical.)
 *
 * The notification inspector (beta, off) writes down how the chosen apps build their ongoing
 * notifications (a ride, a route, a timer…), so 1.6 can read them from real examples.
 */
class NotifListener : NotificationListenerService() {
    companion object {
        fun logFile(ctx: Context) = File(ctx.filesDir, "inspector.txt")
        private const val MAX_LOG = 400_000L
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = Prefs(this)
        if (prefs.inspector && sbn.packageName in prefs.inspectorApps) inspect(sbn)
    }

    /** One block per update: what the app put in the notification (text fields, progress, timer, buttons). */
    private val lastLogged = HashMap<String, String>()

    private fun inspect(sbn: StatusBarNotification) {
        try {
            val n = sbn.notification
            val x = n.extras
            val sb = StringBuilder()
            fun add(k: String, v: Any?) {
                val s = v?.toString()?.trim()
                if (!s.isNullOrEmpty()) sb.append("  ").append(k).append(": ").append(s.replace('\n', ' ')).append('\n')
            }
            add("ongoing", sbn.isOngoing)
            add("category", n.category)
            add("channel", n.channelId)
            add("title", x.getCharSequence(Notification.EXTRA_TITLE))
            add("text", x.getCharSequence(Notification.EXTRA_TEXT))
            add("subText", x.getCharSequence(Notification.EXTRA_SUB_TEXT))
            add("bigText", x.getCharSequence(Notification.EXTRA_BIG_TEXT))
            add("info", x.getCharSequence(Notification.EXTRA_INFO_TEXT))
            add("summary", x.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
            x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.let { add("lines", it.joinToString(" | ")) }
            if (x.containsKey(Notification.EXTRA_PROGRESS_MAX)) {
                add("progress", "${x.getInt(Notification.EXTRA_PROGRESS)}/${x.getInt(Notification.EXTRA_PROGRESS_MAX)} indeterminate=${x.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)}")
            }
            if (x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) {
                add("chronometer", "base=${n.`when`} countDown=${x.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)}")
            }
            add("when", n.`when`)
            add("template", x.getString(Notification.EXTRA_TEMPLATE))
            add("custom view", n.contentView != null || n.bigContentView != null)
            n.actions?.let { a -> add("buttons", a.joinToString(" | ") { it.title?.toString().orEmpty() }) }
            add("keys", x.keySet().sorted().joinToString(","))
            val body = sb.toString()
            val key = "${sbn.packageName}/${sbn.id}"
            if (lastLogged[key] == body) return
            lastLogged[key] = body
            val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val f = logFile(this)
            if (f.length() > MAX_LOG) f.writeText(f.readText().takeLast((MAX_LOG / 2).toInt()))
            f.appendText("[$time] ${sbn.packageName} #${sbn.id}\n$body\n")
        } catch (e: Exception) {
            // Never break the listener over the log.
        }
    }
}
