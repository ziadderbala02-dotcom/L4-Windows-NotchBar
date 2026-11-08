package com.l4.notchlive

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.StatusBarNotification

/**
 * One live activity from another app, as the notch shows it: a big value on the camera's left
 * (minutes, a time, a percentage, a running timer), a short label on its right (a car plate…).
 */
data class Live(
    val key: String,
    val pkg: String,
    val kind: String, // "ride", "app", "call", "timer", "media"
    val title: String,
    val text: String,
    val big: String,
    val right: String,
    val progress: Float, // 0..1, or -1 for none
    val chronoBase: Long, // when a running timer counts from/to, 0 for none
    val countDown: Boolean,
    val intent: PendingIntent?,
    val at: Long,
    /** When the ride / order is due (from "in 2 min" or "at 12:40 PM"), 0 if unknown: the circle counts down to it. */
    val target: Long = 0L,
)

/**
 * Reads live activities out of other apps' notifications. It never relies on the words (which
 * change with the app's language): it uses the notification's structure (channel, progress, a
 * running timer), numbers next to "min"/"د", clock times, and the "plate • car" pattern.
 *
 * Built from a real Uber log: the trip is one notification on the "trip_take_ride_channels"
 * channel, updated through the trip ("Pick up in 2 min", "Mahmoud arrived", "Dropoff at 12:40 PM",
 * with "ك ب ط 8925 • White BYD F3" under it), and it is not marked ongoing during the ride.
 */
object Activities {
    /** Ride and delivery apps offered first in the picker (only the ones installed show). */
    val SUGGESTED = listOf(
        "com.ubercab", "sinet.startup.inDriver", "com.careem.acma", "com.didiglobal.passenger", "ee.mtakso.client",
        "com.talabat", "com.breadfast", "com.mrsool.customer", "com.instabug.mrsool", "com.swvl.android",
    )

    @Volatile
    var list: List<Live> = emptyList()

    /** The phone's own music, on phones without a pill round the camera. */
    @Volatile
    var media: MediaController? = null

    private val rideWords = listOf("trip", "ride", "order", "deliver", "courier", "booking")

    fun scan(ctx: Context, all: Array<StatusBarNotification>) {
        val prefs = Prefs(ctx)
        if (!prefs.liveActs) {
            list = emptyList()
            return
        }
        val apps = prefs.liveApps
        val pill = SystemPill.pillOn(ctx)
        val out = ArrayList<Live>()
        // Ride / delivery / any chosen app: the notification that carries the trip or order.
        for ((pkg, group) in all.filter { it.packageName in apps }.groupBy { it.packageName }) {
            val picked = group
                .filter { title(it).isNotBlank() }
                .filter { s ->
                    val n = s.notification
                    val ch = n.channelId.orEmpty().lowercase()
                    s.isOngoing || n.extras.getInt(Notification.EXTRA_PROGRESS_MAX) > 0 ||
                        n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) || rideWords.any { it in ch }
                }
                // The custom-layout one is the live card (Uber); otherwise the newest.
                .sortedWith(compareBy({ if (it.notification.contentView != null || it.notification.bigContentView != null) 0 else 1 }, { -it.postTime }))
                .firstOrNull() ?: continue
            out += build(ctx, picked, if (pkg in SUGGESTED) "ride" else "app")
        }
        // Calls and timers (any app), where the phone has no pill of its own to show them.
        if (!pill && prefs.liveSystem) {
            for (s in all) {
                if (s.packageName == ctx.packageName || s.packageName in apps || !s.isOngoing) continue
                val n = s.notification
                val chrono = n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
                if (n.category == Notification.CATEGORY_CALL || chrono) {
                    out += build(ctx, s, if (n.category == Notification.CATEGORY_CALL) "call" else "timer")
                }
            }
        }
        list = out
    }

    private fun title(s: StatusBarNotification) = s.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    private fun text(s: StatusBarNotification) = s.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

    private fun build(ctx: Context, s: StatusBarNotification, kind: String): Live {
        val n = s.notification
        val x = n.extras
        val t = title(s)
        val tx = text(s)
        val max = x.getInt(Notification.EXTRA_PROGRESS_MAX)
        val prog = if (max > 0) x.getInt(Notification.EXTRA_PROGRESS) / max.toFloat() else -1f
        val chrono = x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val (big, right) = parse(t, tx, prog)
        return Live(
            key = s.key, pkg = s.packageName, kind = kind, title = t, text = tx,
            big = big, right = right, progress = prog,
            chronoBase = if (chrono) n.`when` else 0L,
            countDown = chrono && x.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
            intent = n.contentIntent, at = s.postTime,
            target = target(t, s.postTime),
        )
    }

    private val minutes = Regex("(\\d{1,3})\\s*(min|mins|minutes|minute|دقيقة|دقائق|دقيقه|دقايق|د)(?![\\p{L}])", RegexOption.IGNORE_CASE)
    private val clock = Regex("(\\d{1,2}:\\d{2})\\s*(AM|PM|ص|م)?", RegexOption.IGNORE_CASE)

    /** Eastern-Arabic digits to ASCII, so "٢ د" reads like "2 min". */
    private fun ascii(s: String) = buildString { for (ch in s) append(if (ch in '٠'..'٩') '0' + (ch - '٠') else ch) }

    /** When "in 2 min" / "at 12:40 PM" in [title] falls, counted from when the app posted it. */
    fun target(title: String, posted: Long): Long {
        val t = ascii(title)
        minutes.find(t)?.let { return posted + it.groupValues[1].toLong() * 60_000L }
        val m = clock.find(t) ?: return 0L
        val (hs, ms) = m.groupValues[1].split(":")
        var h = hs.toInt()
        val min = ms.toInt()
        val ap = m.groupValues[2].lowercase()
        if (ap == "pm" || ap == "م") { if (h < 12) h += 12 } else if ((ap == "am" || ap == "ص") && h == 12) h = 0
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = posted
            set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, min); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        // No AM/PM: the nearest time ahead (12:40 could be noon or after midnight).
        if (ap.isEmpty() && h < 12) while (cal.timeInMillis < posted - 5 * 60_000L) cal.add(java.util.Calendar.HOUR_OF_DAY, 12)
        else if (cal.timeInMillis < posted - 5 * 60_000L) cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    /** (big value, right label). */
    fun parse(title: String, text: String, progress: Float): Pair<String, String> {
        val t = ascii(title)
        val plate = text.substringBefore("•", "").trim().takeIf { "•" in text && it.length in 2..16 }
        val car = text.substringAfter("•", "").trim()
        val big = minutes.find(t)?.let { "${it.groupValues[1]} ${L.t("min_short")}" }
            ?: clock.find(t)?.let { m -> m.groupValues[1] + (m.groupValues[2].takeIf { it.isNotBlank() }?.let { " $it" } ?: "") }
            ?: if (progress >= 0f) "${(progress * 100).toInt()}%" else ""
        val right = plate ?: when {
            big.isEmpty() -> title
            car.isNotEmpty() -> car
            else -> text.ifBlank { title }
        }
        return (big.ifEmpty { title }) to right
    }

    /* ---------- media (phones without a pill) ---------- */

    /** The playing session, as a live activity (title on the left, artist on the right). */
    fun mediaLive(ctx: Context): Live? {
        val prefs = Prefs(ctx)
        if (!prefs.liveActs || !prefs.liveMedia || SystemPill.pillOn(ctx)) {
            media = null
            return null
        }
        return try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val c = msm.getActiveSessions(ComponentName(ctx, NotifListener::class.java))
                .firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && it.packageName != ctx.packageName }
            media = c
            c ?: return null
            val md = c.metadata
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            Live("media:${c.packageName}", c.packageName, "media", title, artist, title, artist, -1f, 0L, false, c.sessionActivity, System.currentTimeMillis())
        } catch (e: Exception) {
            null
        }
    }

    fun mediaArt(): Bitmap? = try {
        media?.metadata?.let { it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART) }
    } catch (e: Exception) {
        null
    }
}
