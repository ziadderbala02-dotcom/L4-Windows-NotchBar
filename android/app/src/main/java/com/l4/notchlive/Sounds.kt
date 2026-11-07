package com.l4.notchlive

import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.media.SoundPool

/**
 * The Windows app's sounds (goal = stadium, incident = whistle, kickoff = ding), as WAVs.
 * Each kind of event can instead use the phone's notification sound, or be silent (settings).
 */
class Sounds(private val ctx: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                // Notification usage: silent / vibrate mode mutes it, like any notification.
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val goal = pool.load(ctx, R.raw.goal, 1)
    private val whistle = pool.load(ctx, R.raw.whistle, 1)
    private val ding = pool.load(ctx, R.raw.ding, 1)
    private val conceded = pool.load(ctx, R.raw.conceded, 1)

    companion object {
        /** The settings group a burst kind belongs to: goal, card, whistle or news. */
        fun group(kind: String): String = when (kind) {
            "goal", "conceded", "neutral" -> "goal"
            "red", "var", "pen" -> "card"
            "news", "phone" -> "news"
            else -> "whistle"
        }
    }

    fun play(kind: String) {
        val prefs = Prefs(ctx)
        when (prefs.soundFor(group(kind))) {
            "off" -> return
            "phone" -> {
                try {
                    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
                    RingtoneManager.getRingtone(ctx, uri)?.play()
                } catch (e: Exception) {
                    // No default sound set.
                }
                return
            }
        }
        val id = when (kind) {
            "goal" -> goal
            "conceded" -> conceded
            "notice", "news", "neutral" -> ding
            else -> whistle
        }
        val v = (prefs.volume / 100f).coerceIn(0f, 1f)
        pool.play(id, v, v, 1, 0, 1f)
    }

    fun release() = pool.release()
}
