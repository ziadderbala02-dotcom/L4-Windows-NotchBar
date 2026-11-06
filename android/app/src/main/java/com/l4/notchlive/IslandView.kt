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
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A moment that widens the notch for a few seconds: goal, red card, VAR, whistle, a notice. */
class Burst(
    /** goal, red, var, pen, whistle, notice */
    val kind: String,
    val title: String,
    var sub: String,
    val home: Boolean,
    val color: Int,
    val matchId: Int,
)

/**
 * The notch, drawn by hand around the punch-hole camera at [camX] (view coordinates).
 *   compact: [+4][92'] [crest] 2  (camera)  1 [crest] ▮   — wider on the left, where the status
 *            bar has more room; drops the minute, then the crests, if it doesn't fit.
 *   big (t → 1): a burst (goal, red card…) or the info view after a tap (names + scorers).
 *   tucked: a small dot right of the camera (swiped away).
 */
@SuppressLint("ViewConstructor")
class IslandView(ctx: Context, private val gestures: Gestures) : View(ctx) {
    interface Gestures {
        fun tap()
        fun swipe(right: Boolean)
    }

    var match: Match? = null
        set(v) {
            val old = field
            if (old != null && v != null && old.id == v.id) {
                if (v.home.score != old.home.score) homeFlash = System.currentTimeMillis()
                if (v.away.score != old.away.score) awayFlash = System.currentTimeMillis()
            }
            field = v
        }

    /** When each score last changed: it glows in the team colour for a moment. */
    private var homeFlash = 0L
    private var awayFlash = 0L
    var clock: String = ""
    var added: String = ""
    var burst: Burst? = null
    /** The tap view (names + scorers) instead of a burst. */
    var info = false
    var tucked = false

    /** 0 = compact, 1 = big (animated). */
    var t = 0f
        set(v) {
            field = v
            invalidate()
        }

    /** Camera centre x inside this view, its radius, and the room the status bar leaves each side. */
    var camX = 0f
    var camR = 0f
    var leftMax = Float.MAX_VALUE
    var rightMax = Float.MAX_VALUE
    var scale = 1f

    /** Everything is drawn 10% above Android's base size (Omar: "a little bigger"), times the size slider. */
    private val d get() = resources.displayMetrics.density * scale * 1.1f

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val score = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val minute = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFBBF24.toInt()
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
    private val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val line = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC8C8C8.toInt() }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    private fun sizes() {
        score.textSize = 13.5f * d
        minute.textSize = 11f * d
        small.textSize = 8.5f * d
        title.textSize = 16f * d
        sub.textSize = 12f * d
        name.textSize = 12.5f * d
        line.textSize = 10.5f * d
    }

    /* ---------- geometry ---------- */

    fun compactHeight(): Int = max(2 * (camR + 5 * d), 28 * d).toInt()

    private fun gap() = camR + 6 * d
    private fun crestSize() = compactHeight() - 10 * d
    private fun reds(home: Boolean) = match?.reds(home) ?: 0
    private fun redsWidth(home: Boolean) = if (reds(home) > 0) min(reds(home), 3) * 4 * d + 3 * d else 0f

    private fun minuteColor(m: Match): Int = when {
        m.state == Api.POST -> 0xFFB4B4B4.toInt()
        m.phase == "break" -> 0xFFFBBF24.toInt()
        m.phase == "var" -> 0xFF60A5FA.toInt()
        else -> 0xFF34D399.toInt()
    }

    private fun clockLabel(m: Match) = if (m.phase == "var") "VAR" else clock

    /** 0 = everything, 1 = no minute, 2 = scores only. The first one that fits the room. */
    private fun tier(): Int {
        for (k in 0..2) if (leftWidth(k) <= leftMax && rightWidth(k) <= rightMax) return k
        return 2
    }

    private fun leftWidth(k: Int): Float {
        sizes()
        val m = match ?: return gap()
        var w = gap() + score.measureText(m.home.score.toString()) + 10 * d
        if (k < 2) w += 5 * d + crestSize()
        if (k < 1) {
            w += 6 * d + minute.measureText(clockLabel(m))
            if (added.isNotEmpty()) w += 2 * d + small.measureText(added)
        }
        return w
    }

    private fun rightWidth(k: Int): Float {
        sizes()
        val m = match ?: return gap()
        var w = gap() + score.measureText(m.away.score.toString()) + 10 * d
        if (k < 2) w += 5 * d + crestSize()
        w += redsWidth(false)
        w += 6 * d + 6 * d
        return w
    }

    /** Pill extent from the camera centre: (left, right). */
    fun compactExtent(): Pair<Float, Float> {
        if (tucked) return 0f to 0f
        val k = tier()
        return leftWidth(k) to rightWidth(k)
    }

    /** The tucked mark: a window right of the camera, wide enough to swipe on. */
    fun tuckWidth(): Int = (40 * d).toInt()

    fun bigWidth(screenW: Int): Int = min(screenW - (24 * resources.displayMetrics.density).toInt(), (360 * d).toInt())

    private fun top() = compactHeight() / 2f + camR + 6 * d

    private fun goals(home: Boolean) = match?.events?.filter { it.kind == "goal" && it.home == home } ?: emptyList()

    private fun infoLines(): Int = min(4, max(goals(true).size, goals(false).size).let { if (it > 3) 4 else it })

    fun bigHeight(): Int = if (info && burst == null) (top() + 46 * d + infoLines() * 15 * d + 8 * d).toInt()
    else (top() + 52 * d).toInt()

    /* ---------- touch: tap / swipe ---------- */

    private var downX = 0f
    private var downY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
            }
            MotionEvent.ACTION_UP -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                val far = 36 * resources.displayMetrics.density
                if (abs(dx) > far && abs(dx) > abs(dy)) gestures.swipe(dx > 0)
                else if (abs(dx) < 12 * resources.displayMetrics.density && abs(dy) < 12 * resources.displayMetrics.density) gestures.tap()
            }
        }
        return true
    }

    /* ---------- drawing ---------- */

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    override fun onDraw(c: Canvas) {
        sizes()
        if (tucked && burst == null && !info) {
            drawTuck(c)
            return
        }
        val m = match ?: return
        val ch = compactHeight().toFloat()
        val (lw, rw) = compactExtent()
        val bw = bigWidth(resources.displayMetrics.widthPixels).toFloat()
        val bh = bigHeight().toFloat()
        val l = lerp(camX - lw, camX - bw / 2, t)
        val r = lerp(camX + rw, camX + bw / 2, t)
        val h = lerp(ch, bh, t)
        val radius = lerp(ch / 2, 24 * d, t)
        rect.set(l, 0f, r, h)
        c.drawRoundRect(rect, radius, radius, bg)

        val b = burst
        val accent = b?.color ?: if (info) 0xFF3F3F46.toInt() else null
        if (accent != null && t > 0.05f) {
            border.color = accent
            border.strokeWidth = 1.5f * d
            // Whistle and goal pulse a little.
            val pulse = if (b != null && (b.kind == "goal" || b.kind == "whistle")) 0.65f + 0.35f * abs(sin(System.currentTimeMillis() / 220.0)).toFloat() else 1f
            border.alpha = (min(1f, t) * 220 * pulse).toInt()
            rect.inset(border.strokeWidth / 2, border.strokeWidth / 2)
            c.drawRoundRect(rect, radius, radius, border)
            if (b != null && (b.kind == "goal" || b.kind == "whistle")) postInvalidateOnAnimation()
        }

        val compactAlpha = (1f - t * 2.5f).coerceIn(0f, 1f)
        if (compactAlpha > 0f) drawCompact(c, m, ch, compactAlpha)
        val bigAlpha = ((t - 0.5f) * 2f).coerceIn(0f, 1f)
        if (bigAlpha > 0f) {
            if (b != null) drawBurst(c, m, b, camX - bw / 2, camX + bw / 2, h, bigAlpha)
            else if (info) drawInfo(c, m, camX - bw / 2, camX + bw / 2, bigAlpha)
        }
    }

    private fun drawTuck(c: Canvas) {
        val ch = compactHeight().toFloat()
        val m = match
        dot.color = if (m != null && m.state == Api.LIVE && m.phase == "play") 0xFF34D399.toInt() else 0xFFFBBF24.toInt()
        dot.alpha = 255
        c.drawCircle(8 * d, ch / 2, 3.5f * d, dot)
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

    private fun baseline(p: Paint, cy: Float) = cy - (p.descent() + p.ascent()) / 2

    private fun drawCompact(c: Canvas, m: Match, ch: Float, alpha: Float) {
        val a = (alpha * 255).toInt()
        val k = tier()
        val crest = crestSize()
        val cy = ch / 2
        score.alpha = a

        // Left of the camera, outward: home score, crest (+ red cards), minute (+ added time).
        var x = camX - gap()
        val hs = m.home.score.toString()
        x -= score.measureText(hs)
        drawScore(c, hs, x, cy, m.home.color, homeFlash, a)
        if (k < 2) {
            x -= 5 * d + crest
            drawCrest(c, m.home.logo, x, cy - crest / 2, crest, alpha)
            if (reds(true) > 0) drawRedCards(c, reds(true), x - 2 * d, cy + crest / 2 - 5 * d, alpha)
        }
        if (k < 1) {
            val label = clockLabel(m)
            minute.color = minuteColor(m)
            minute.alpha = a
            x -= 6 * d + minute.measureText(label)
            c.drawText(label, x, baseline(minute, cy), minute)
            if (added.isNotEmpty()) {
                small.alpha = a
                x -= 2 * d + small.measureText(added)
                c.drawText(added, x, cy - 1 * d, small)
            }
        }

        // Right of the camera, outward: away score, crest, red cards.
        x = camX + gap()
        val aw = m.away.score.toString()
        drawScore(c, aw, x, cy, m.away.color, awayFlash, a)
        x += score.measureText(aw)
        if (k < 2) {
            x += 5 * d
            drawCrest(c, m.away.logo, x, cy - crest / 2, crest, alpha)
            x += crest
        }
        if (reds(false) > 0) {
            drawRedCards(c, reds(false), x + 3 * d, cy - 2.5f * d, alpha)
            x += redsWidth(false)
        }

        // The live dot: breathes while the ball is rolling, amber at a break, grey when over.
        val now = System.currentTimeMillis()
        val (color, breathe) = when {
            m.state == Api.POST -> 0xFF6B7280.toInt() to false
            m.phase == "break" -> 0xFFFBBF24.toInt() to false
            m.phase == "var" -> 0xFF60A5FA.toInt() to true
            else -> 0xFF34D399.toInt() to true
        }
        val level = if (breathe) 0.35f + 0.65f * ((sin(now / 450.0) + 1) / 2).toFloat() else 1f
        dot.color = color
        dot.alpha = (a * level).toInt()
        c.drawCircle(x + 6 * d + 3 * d, cy, 3 * d, dot)
        if (breathe) {
            dot.alpha = (a * level * 0.25f).toInt()
            c.drawCircle(x + 6 * d + 3 * d, cy, 5.5f * d, dot)
            postInvalidateDelayed(50)
        }
    }

    /** A score; for 4 s after it changes it glows in the team colour and fades back to white. */
    private fun drawScore(c: Canvas, s: String, x: Float, cy: Float, team: Int, since: Long, a: Int) {
        val age = System.currentTimeMillis() - since
        if (age in 0..4000) {
            val f = 1f - age / 4000f
            score.color = blend(Color.WHITE, team, f)
            dot.color = team
            dot.alpha = (a * f * 0.35f).toInt()
            c.drawCircle(x + score.measureText(s) / 2, cy, 9 * d, dot)
            postInvalidateDelayed(50)
        } else {
            score.color = Color.WHITE
        }
        score.alpha = a
        c.drawText(s, x, baseline(score, cy), score)
        score.color = Color.WHITE
        score.alpha = a
    }

    private fun blend(a: Int, b: Int, f: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt(),
    )

    /** A little whistle: body, mouthpiece and sound lines. */
    private fun drawWhistle(c: Canvas, cx: Float, cy: Float, color: Int, alpha: Float) {
        dot.color = color
        dot.alpha = (alpha * 255).toInt()
        c.drawCircle(cx - 3 * d, cy + 3 * d, 9 * d, dot)
        rect.set(cx - 3 * d, cy - 6 * d, cx + 13 * d, cy + 1 * d)
        c.drawRoundRect(rect, 2 * d, 2 * d, dot)
        dot.color = Color.BLACK
        c.drawCircle(cx - 3 * d, cy + 3 * d, 3.5f * d, dot)
        stroke.color = color
        stroke.strokeWidth = 1.8f * d
        stroke.strokeCap = Paint.Cap.ROUND
        val wave = (System.currentTimeMillis() / 120 % 3).toInt()
        for (i in 0..2) {
            stroke.alpha = ((if (i <= wave) 1f else 0.25f) * alpha * 255).toInt()
            val o = 5 * d + i * 4 * d
            c.drawLine(cx + 13 * d + o * 0.4f, cy - 10 * d - i * 2 * d, cx + 15 * d + o * 0.6f, cy - 14 * d - i * 3 * d, stroke)
        }
    }

    private fun drawBurst(c: Canvas, m: Match, b: Burst, l: Float, r: Float, h: Float, alpha: Float) {
        val a = (alpha * 255).toInt()
        val top = top()
        val bottom = h - 6 * d
        val mid = (top + bottom) / 2
        val left = l + 14 * d
        val right = r - 14 * d
        val crest = 34 * d

        if (b.kind == "whistle") drawWhistle(c, left + crest / 2, mid, b.color, alpha)
        else drawCrest(c, if (b.home) m.home.logo else m.away.logo, left, mid - crest / 2, crest, alpha)

        val sc = "${m.home.score} - ${m.away.score}"
        score.alpha = a
        val big = score.textSize
        score.textSize = 18f * d
        val sw = score.measureText(sc)
        c.drawText(sc, right - sw, baseline(score, mid), score)
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
            c.drawText(tt, tx, baseline(title, mid), title)
        } else {
            c.drawText(tt, tx, mid - 3 * d, title)
            c.drawText(st, tx, mid + 3 * d - sub.ascent(), sub)
        }
    }

    private fun scorerText(e: Ev): String {
        val tag = when {
            e.own -> " (عكسي)"
            e.pen -> " (ج)"
            else -> ""
        }
        return "${e.player ?: "جون"}$tag ${e.minute}".trim()
    }

    /** After a tap: crest + name each side, the score and minute in the middle, scorers below. */
    private fun drawInfo(c: Canvas, m: Match, l: Float, r: Float, alpha: Float) {
        val a = (alpha * 255).toInt()
        val top = top()
        val left = l + 14 * d
        val right = r - 14 * d
        val crest = 26 * d
        val rowMid = top + 16 * d

        val sc = "${m.home.score} - ${m.away.score}"
        score.alpha = a
        val big = score.textSize
        score.textSize = 19f * d
        score.textAlign = Paint.Align.CENTER
        val sw = score.measureText(sc)
        c.drawText(sc, camX, baseline(score, rowMid), score)
        score.textSize = big
        score.textAlign = Paint.Align.LEFT
        val label = clockLabel(m) + if (added.isNotEmpty()) "  $added" else ""
        minute.color = minuteColor(m)
        minute.alpha = a
        minute.textAlign = Paint.Align.CENTER
        c.drawText(label, camX, rowMid + 12 * d - minute.ascent() / 2, minute)
        minute.textAlign = Paint.Align.LEFT

        val nameW = max(20f, (right - left - sw) / 2 - crest - 14 * d)
        name.alpha = a
        drawCrest(c, m.home.logo, left, rowMid - crest / 2, crest, alpha)
        name.textAlign = Paint.Align.LEFT
        c.drawText(TextUtils.ellipsize(m.home.short, name, nameW, TextUtils.TruncateAt.END).toString(), left + crest + 6 * d, baseline(name, rowMid), name)
        drawCrest(c, m.away.logo, right - crest, rowMid - crest / 2, crest, alpha)
        name.textAlign = Paint.Align.RIGHT
        c.drawText(TextUtils.ellipsize(m.away.short, name, nameW, TextUtils.TruncateAt.END).toString(), right - crest - 6 * d, baseline(name, rowMid), name)
        name.textAlign = Paint.Align.LEFT

        // Scorers: up to 3 a side, then "+N".
        line.alpha = a
        val colW = (right - left) / 2 - 8 * d
        for (home in listOf(true, false)) {
            val gs = goals(home)
            val shown = gs.take(3).map { "⚽ " + scorerText(it) }.toMutableList()
            if (gs.size > 3) shown += "+${gs.size - 3}"
            line.textAlign = if (home) Paint.Align.LEFT else Paint.Align.RIGHT
            shown.forEachIndexed { i, s ->
                val y = top + 40 * d + i * 15 * d - line.ascent()
                c.drawText(TextUtils.ellipsize(s, line, colW, TextUtils.TruncateAt.END).toString(), if (home) left else right, y, line)
            }
        }
        line.textAlign = Paint.Align.LEFT
    }
}
