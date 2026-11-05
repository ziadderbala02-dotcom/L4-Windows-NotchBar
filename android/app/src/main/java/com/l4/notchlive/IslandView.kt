package com.l4.notchlive

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** A moment that widens the notch for a few seconds: goal, red card, VAR, a notice. */
class Burst(
    /** goal, red, var, pen, notice */
    val kind: String,
    val title: String,
    var sub: String,
    val home: Boolean,
    val color: Int,
    val matchId: Int,
)

/**
 * The notch itself, drawn by hand around the punch-hole camera.
 *   compact (t = 0): [minute] [crest] 2  (camera)  1 [crest] [•]
 *   burst   (t = 1): wider and taller; under the camera: crest | title + line | score
 * The window is always centred on the camera, so the pill is drawn centred in the view.
 */
@SuppressLint("ViewConstructor")
class IslandView(ctx: Context) : View(ctx) {
    var match: Match? = null
    var clock: String = ""
    var burst: Burst? = null

    /** 0 = compact, 1 = burst (animated). */
    var t = 0f
        set(v) {
            field = v
            invalidate()
        }

    /** Camera radius in px and the size multiplier from Settings. */
    var camR = 0f
    var scale = 1f

    private val d get() = resources.displayMetrics.density * scale

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val score = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val minute = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF34D399.toInt()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB4B4B4.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    private fun sizes() {
        score.textSize = 13.5f * d
        minute.textSize = 11f * d
        title.textSize = 16f * d
        sub.textSize = 12f * d
    }

    /* ---------- geometry ---------- */

    fun compactHeight(): Int = max(2 * (camR + 5 * d), 28 * d).toInt()

    private fun gap() = 2 * camR + 10 * d
    private fun crestSize() = compactHeight() - 10 * d

    private fun minuteText(): String = when (clock) {
        "بين الشوطين" -> "استراحة"
        else -> clock
    }

    fun compactWidth(): Int {
        sizes()
        val m = match ?: return compactHeight()
        val crest = crestSize()
        val pad = 10 * d
        val left = pad + minute.measureText(minuteText()) + 6 * d + crest + 5 * d + score.measureText(m.home.score.toString())
        val right = score.measureText(m.away.score.toString()) + 5 * d + crest + 6 * d + 6 * d + pad
        return (2 * max(left, right) + gap()).toInt()
    }

    fun burstHeight(): Int = (compactHeight() / 2f + camR + 6 * d + 52 * d).toInt()

    fun burstWidth(screenW: Int): Int = min(screenW - (24 * resources.displayMetrics.density).toInt(), (360 * d).toInt())

    /* ---------- drawing ---------- */

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    override fun onDraw(c: Canvas) {
        val m = match ?: return
        sizes()
        val cw = compactWidth().toFloat()
        val ch = compactHeight().toFloat()
        val bw = burstWidth(resources.displayMetrics.widthPixels).toFloat()
        val bh = burstHeight().toFloat()
        val w = lerp(cw, bw, t)
        val h = lerp(ch, bh, t)
        val cx = width / 2f
        val radius = lerp(ch / 2, 24 * d, t)
        rect.set(cx - w / 2, 0f, cx + w / 2, h)
        c.drawRoundRect(rect, radius, radius, bg)

        val b = burst
        if (b != null && t > 0.05f) {
            border.color = b.color
            border.strokeWidth = 1.5f * d
            border.alpha = (min(1f, t) * 200).toInt()
            rect.inset(border.strokeWidth / 2, border.strokeWidth / 2)
            c.drawRoundRect(rect, radius, radius, border)
        }

        val compactAlpha = (1f - t * 2.5f).coerceIn(0f, 1f)
        if (compactAlpha > 0f) drawCompact(c, m, cx, ch, compactAlpha)
        val burstAlpha = ((t - 0.5f) * 2f).coerceIn(0f, 1f)
        if (b != null && burstAlpha > 0f) drawBurst(c, m, b, cx, w, h, ch, burstAlpha)
    }

    private fun drawCrest(c: Canvas, url: String, l: Float, top: Float, size: Float, alpha: Float) {
        val bm = Logos.get(url) { invalidate() } ?: run {
            dot.color = 0xFF3A3A3A.toInt()
            dot.alpha = (alpha * 255).toInt()
            c.drawCircle(l + size / 2, top + size / 2, size / 2.4f, dot)
            return
        }
        bmp.alpha = (alpha * 255).toInt()
        rect.set(l, top, l + size, top + size)
        c.drawBitmap(bm, null, rect, bmp)
    }

    private fun drawRedCards(c: Canvas, n: Int, x: Float, y: Float, alpha: Float) {
        dot.color = 0xFFEF4444.toInt()
        dot.alpha = (alpha * 255).toInt()
        for (i in 0 until min(n, 3)) {
            rect.set(x + i * 4 * d, y, x + i * 4 * d + 3 * d, y + 5 * d)
            c.drawRoundRect(rect, 0.6f * d, 0.6f * d, dot)
        }
    }

    private fun drawCompact(c: Canvas, m: Match, cx: Float, ch: Float, alpha: Float) {
        val a = (alpha * 255).toInt()
        val crest = crestSize()
        val cy = ch / 2
        val baseline = { p: Paint -> cy - (p.descent() + p.ascent()) / 2 }
        score.alpha = a
        minute.alpha = a

        // Left of the camera: minute, home crest, home score.
        var x = cx - gap() / 2
        val hs = m.home.score.toString()
        x -= score.measureText(hs)
        c.drawText(hs, x, baseline(score), score)
        x -= 5 * d + crest
        drawCrest(c, m.home.logo, x, cy - crest / 2, crest, alpha)
        if (m.reds(true) > 0) drawRedCards(c, m.reds(true), x - 2 * d, cy + crest / 2 - 5 * d, alpha)
        val mt = minuteText()
        minute.color = when {
            m.state == Api.POST -> 0xFFB4B4B4.toInt()
            m.phase == "break" -> 0xFFFBBF24.toInt()
            m.phase == "var" -> 0xFF60A5FA.toInt()
            else -> 0xFF34D399.toInt()
        }
        minute.alpha = a
        x -= 6 * d + minute.measureText(mt)
        c.drawText(mt, x, baseline(minute), minute)

        // Right of the camera: away score, away crest, live dot.
        x = cx + gap() / 2
        c.drawText(m.away.score.toString(), x, baseline(score), score)
        x += score.measureText(m.away.score.toString()) + 5 * d
        drawCrest(c, m.away.logo, x, cy - crest / 2, crest, alpha)
        if (m.reds(false) > 0) drawRedCards(c, m.reds(false), x + crest - 6 * d, cy + crest / 2 - 5 * d, alpha)
        x += crest + 6 * d
        dot.color = if (m.state == Api.LIVE && m.phase == "play") 0xFF34D399.toInt() else 0xFF6B7280.toInt()
        // Blink once a second while the ball is rolling.
        val blink = m.state == Api.LIVE && m.phase == "play" && (System.currentTimeMillis() / 1000) % 2 == 0L
        dot.alpha = if (blink) a / 3 else a
        c.drawCircle(x + 3 * d, cy, 3 * d, dot)
    }

    private fun drawBurst(c: Canvas, m: Match, b: Burst, cx: Float, w: Float, h: Float, ch: Float, alpha: Float) {
        val a = (alpha * 255).toInt()
        val top = ch / 2 + camR + 6 * d
        val bottom = h - 6 * d
        val mid = (top + bottom) / 2
        val left = cx - w / 2 + 14 * d
        val right = cx + w / 2 - 14 * d
        val crest = 34 * d

        drawCrest(c, b.let { if (it.home) m.home.logo else m.away.logo }, left, mid - crest / 2, crest, alpha)

        val sc = "${m.home.score} - ${m.away.score}"
        score.alpha = a
        val big = score.textSize
        score.textSize = 18f * d
        val sw = score.measureText(sc)
        c.drawText(sc, right - sw, mid - (score.descent() + score.ascent()) / 2, score)
        score.textSize = big

        val textL = left + crest + 10 * d
        val textR = right - sw - 10 * d
        val tx = (textL + textR) / 2
        val avail = max(20f, textR - textL)
        title.color = b.color
        title.alpha = a
        sub.alpha = a
        val tt = TextUtils.ellipsize(b.title, title, avail, TextUtils.TruncateAt.END).toString()
        val st = TextUtils.ellipsize(b.sub, sub, avail, TextUtils.TruncateAt.END).toString()
        if (st.isBlank()) {
            c.drawText(tt, tx, mid - (title.descent() + title.ascent()) / 2, title)
        } else {
            c.drawText(tt, tx, mid - 3 * d, title)
            c.drawText(st, tx, mid + 3 * d - sub.ascent(), sub)
        }
    }
}
