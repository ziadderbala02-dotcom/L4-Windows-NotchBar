package com.l4.notchlive

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/** The Windows app's sounds (goal = stadium, incident = whistle, kickoff = ding), as WAVs. */
class Sounds(ctx: Context) {
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

    fun play(kind: String) {
        val id = when (kind) {
            "goal" -> goal
            "notice" -> ding
            else -> whistle
        }
        pool.play(id, 1f, 1f, 1, 0, 1f)
    }

    fun release() = pool.release()
}
