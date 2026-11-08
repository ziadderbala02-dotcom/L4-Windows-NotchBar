package com.l4.notchlive

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.provider.Settings
import android.service.notification.StatusBarNotification

/**
 * Is the phone's own pill around the camera showing (Xiaomi's island, Honor's Magic Capsule,
 * OPPO / realme / OnePlus Fluid Cloud…)? Then our notch steps aside as a small circle.
 *
 * Those pills all feed on the same thing: the ongoing notification an app puts up for a call,
 * music, a timer, navigation, a recording (and Android 16's "Live Updates"). With notification
 * access we read the same notifications, so a video in Facebook's feed (no such notification)
 * no longer shrinks the notch, while a timer does. Without access: any sound, like before.
 */
object SystemPill {
    /** Brands whose pill sits round the camera. Samsung, Pixel, Motorola… show theirs by the clock: no clash. */
    val hasPill: Boolean by lazy {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        listOf("xiaomi", "redmi", "poco", "honor", "huawei", "oppo", "realme", "oneplus", "vivo", "iqoo").any { it in m }
    }

    /** Packages with an ongoing notification right now that a pill would show (from NotifListener). */
    @Volatile
    var ongoing: Set<String> = emptySet()

    /** Every package with an ongoing notification, and since when (to learn from the user's own swipes). */
    @Volatile
    var allOngoing: Map<String, Long> = emptyMap()

    /** The phone shows a charging animation in its pill for a few seconds after plugging in. */
    @Volatile
    private var chargingUntil = 0L

    private var watching = false

    fun watchCharging(ctx: Context) {
        if (watching) return
        watching = true
        try {
            ctx.applicationContext.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    chargingUntil = System.currentTimeMillis() + 5_000L
                    onCharge?.invoke()
                }
            }, IntentFilter(Intent.ACTION_POWER_CONNECTED))
        } catch (e: Exception) {
            // Not available.
        }
    }

    /** The user's answer wins over the brand guess. */
    fun pillOn(ctx: Context): Boolean = when (Prefs(ctx).pillMode) {
        "yes" -> true
        "no" -> false
        else -> hasPill
    }

    /** Called when a charger is plugged in (phones without a pill show it in our notch). */
    @Volatile
    var onCharge: (() -> Unit)? = null

    fun listenerOn(ctx: Context): Boolean =
        Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(ctx.packageName) == true

    /** Would this notification put something in the phone's pill? */
    fun feedsPill(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (!sbn.isOngoing) return false
        val x = n.extras
        if (Build.VERSION.SDK_INT >= 36 && n.flags and 0x00040000 != 0) return true // FLAG_PROMOTED_ONGOING: a Live Update.
        if (n.category == Notification.CATEGORY_CALL) return true
        if (Build.VERSION.SDK_INT >= 31 && (n.category == Notification.CATEGORY_NAVIGATION || n.category == Notification.CATEGORY_STOPWATCH)) return true
        if (x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) return true // timer, stopwatch, recording, call
        return false
    }

    /** Media playing through a proper session (Spotify, YouTube…): pills show those. */
    private fun mediaPlaying(ctx: Context): Boolean = try {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        msm.getActiveSessions(ComponentName(ctx, NotifListener::class.java))
            .any { it.playbackState?.state == PlaybackState.STATE_PLAYING && it.packageName != ctx.packageName }
    } catch (e: Exception) {
        false
    }

    private var mediaAt = 0L
    private var mediaCache = false

    /** True when the phone's pill is probably showing. */
    fun busy(ctx: Context, audio: AudioManager, inCall: Boolean): Boolean {
        if (!pillOn(ctx)) return false
        val now = System.currentTimeMillis()
        if (inCall || now < chargingUntil) return true
        if (!listenerOn(ctx)) return audio.isMusicActive
        if (now - mediaAt > 2_000L) {
            mediaAt = now
            mediaCache = mediaPlaying(ctx)
        }
        return reason(ctx, audio, inCall).isNotEmpty()
    }

    /**
     * Why the notch is a circle right now ("" when it isn't), shown in Settings so a stuck circle
     * explains itself. A media session or a learned app only counts while sound is really playing:
     * a browser that says "playing" for a muted video (Twitter's feed) kept the circle after 1.7.
     */
    fun reason(ctx: Context, audio: AudioManager, inCall: Boolean): String {
        if (!pillOn(ctx)) return ""
        val now = System.currentTimeMillis()
        if (inCall) return L.t("why_call")
        if (now < chargingUntil) return L.t("why_charge")
        val sound = audio.isMusicActive
        if (!listenerOn(ctx)) return if (sound) L.t("why_sound") else ""
        if (now - mediaAt > 2_000L) {
            mediaAt = now
            mediaCache = mediaPlaying(ctx)
        }
        if (mediaCache && sound) return L.t("why_music")
        ongoing.firstOrNull()?.let { return L.f("why_app", label(ctx, it)) }
        // Apps the user taught us: they swiped the notch away twice while it was there.
        val learned = Prefs(ctx).learnedPill
        if (sound) allOngoing.keys.firstOrNull { it in learned }?.let { return L.f("why_learned", label(ctx, it)) }
        return ""
    }

    fun label(ctx: Context, pkg: String): String = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    /** The user swiped the notch away: whatever ongoing app wasn't known gets a point; two make it known. */
    fun learnFromSwipe(ctx: Context) {
        // Only notifications that came up in the last 2 minutes: an always-there one (a VPN, a step
        // counter) isn't what's covering the camera.
        val now = System.currentTimeMillis()
        val candidates = allOngoing.filter { now - it.value < 120_000L }.keys - ongoing - ctx.packageName
        if (candidates.isEmpty()) return
        val p = Prefs(ctx)
        val counts = p.pillVotes.toMutableMap()
        val learned = p.learnedPill.toMutableSet()
        for (pkg in candidates) {
            val v = (counts[pkg] ?: 0) + 1
            counts[pkg] = v
            if (v >= 2) learned += pkg
        }
        p.pillVotes = counts
        p.learnedPill = learned
    }
}
