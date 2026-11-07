package com.l4.notchlive

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
    /** A goal against a favourite team: no celebration, a low sound. */
    val against: Boolean = false,
    /** News: the team's crest and the article to open. */
    val logo: String? = null,
    val url: String? = null,
    /** Phone notifications: the app's icon. */
    val icon: android.graphics.Bitmap? = null,
    /** menu: the rows. */
    val items: List<String> = emptyList(),
    /** shortcuts: one icon per slot (null = a glyph from [glyphs], or an empty slot). */
    val icons: List<android.graphics.Bitmap?> = emptyList(),
    val glyphs: List<String> = emptyList(),
    /** A goal in a pinned match with no favourite: a quiet celebration in the team colour. */
    val neutral: Boolean = false,
) {
    /** News: the first tap shows the whole headline. */
    var expanded = false
    /** stats: filled in when they arrive (null = loading). */
    @Volatile
    var stats: List<Stat>? = null
    /** When it went on screen, for the animations. */
    var startedAt = 0L
}

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
        /** Vertical swipe: down opens the quick stats, up closes them. */
        fun pull(down: Boolean)
        fun longPress()
        fun menuPick(index: Int)
        /** Long press on a shortcut slot (to change or remove it). */
        fun slotLongPress(index: Int) {}
        /** The "⌄ Shortcuts" row at the bottom of the opened match view. */
        fun shortcuts() {}
    }

    /** Saver mode: no endless breathing animations (bursts still animate). */
    var calm = false

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
    /** Landscape: a pill at the top centre, no camera to wrap around (a "–" between the scores). */
    var landscape = false
    /** Xiaomi's island is busy: a small circle with the score. */
    var circle = false
    /** The status bar is hidden (full screen) and the user allowed it: no squeezing, team names shown. */
    var roomy = false
    /** How many other followed matches are live (a small second dot). */
    var others = 0
    /** No live match: the always-on capsule (next match or last result), drawn dimmer. */
    var idle = false
    /** The always-on ring's size (the user's slider), 0.5..1.5. */
    var idleK = 1f
    /** The user's notch width (px): both sides grow (or shrink) together, items spread out. */
    var stretch = 0f
    /** Events (goal, card, news…) play inside the notch at its own height; a tap opens them big. */
    var inlineEvents = true
    /** Always-on notch: what it shows (left: date / prayer / next match; right: the weather). */
    var idleText = ""
    var idleTemp = ""
    /** Weather animation: "rain", "storm", "snow", "sun", "clouds", "wind", "fog" or "". */
    var weather = ""
    /** Animate the weather until this time (or always when [weatherAlways]). */
    var weatherUntil = 0L
    var weatherAlways = false
    /** Screen recording since this time (0 = not recording): a red dot and a timer. */
    var recSince = 0L
    /** Quick stats for the tap view (possession, shots, on target), when they've arrived. */
    var infoStats: List<Stat>? = null

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
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
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

    private fun gap() = if (landscape) 9 * d else camR + 6 * d

    /** Scores, or nothing before kickoff (the countdown says it all). */
    private fun scoreText(m: Match, home: Boolean) = if (m.state == Api.PRE) "" else m.side(home).score.toString()

    private fun bigScore(m: Match) = if (m.state == Api.PRE) "×" else "${m.home.score} - ${m.away.score}"
    /** The crest is capped (a big camera hole used to make it huge and push everything out). */
    private fun baseCrest() = if (roomy) min(compactHeight() - 8 * d, 26 * d) else min(compactHeight() - 10 * d, 20 * d)
    private fun crestAt(level: Int) = if (level >= 1 && !roomy) baseCrest() * 0.8f else baseCrest()
    private fun reds(home: Boolean) = match?.reds(home) ?: 0
    private fun redsWidth(home: Boolean) = if (reds(home) > 0) min(reds(home), 3) * 4 * d + 3 * d else 0f
    private fun teamName(home: Boolean) = match?.side(home)?.short.orEmpty()
    private fun nameWidth(home: Boolean): Float =
        if (!roomy || teamName(home).isEmpty()) 0f else 5 * d + min(80 * d, name.measureText(teamName(home)))

    private fun minuteColor(m: Match): Int = when {
        m.state == Api.PRE -> 0xFFFBBF24.toInt()
        m.state == Api.POST -> 0xFFB4B4B4.toInt()
        m.phase == "break" -> 0xFFFBBF24.toInt()
        m.phase == "var" -> 0xFF60A5FA.toInt()
        else -> 0xFF34D399.toInt()
    }

    private fun clockLabel(m: Match) = if (m.phase == "var") "VAR" else clock

    /*
     * Each side shrinks on its own, one step at a time, until it fits its room:
     *   left:  0 everything · 1 crest at 80% · 2 no "+N" · 3 no minute · 4 no crest
     *   right: 0 everything · 1 crest at 80% · 2 no crest
     * The score and the live dot always stay; red cards sit on the crest's corner.
     */
    private fun leftLevel(): Int {
        if (roomy) return 0
        for (k in 0..3) if (leftWidth(k) <= leftMax) return k
        return 4
    }

    private fun rightLevel(): Int {
        if (roomy) return 0
        for (k in 0..1) if (rightWidth(k) <= rightMax) return k
        return 2
    }

    private fun leftWidth(k: Int): Float {
        sizes()
        val m = match ?: return gap()
        var w = gap() + score.measureText(scoreText(m, true)) + 10 * d + max(0f, stretch)
        w += if (k < 4) 5 * d + crestAt(k) + nameWidth(true) else redsWidth(true)
        if (k < 3) {
            w += 6 * d + minute.measureText(clockLabel(m))
            if (k < 2 && added.isNotEmpty()) w += 2 * d + small.measureText(added)
        }
        return w
    }

    private fun rightWidth(k: Int): Float {
        sizes()
        val m = match ?: return gap()
        var w = gap() + score.measureText(scoreText(m, false)) + 10 * d + max(0f, stretch)
        w += if (k < 2) 5 * d + crestAt(k) + nameWidth(false) else redsWidth(false)
        w += 6 * d + 6 * d
        if (others > 0) w += 7 * d
        return w
    }

    /** Pill extent from the camera centre: (left, right). */
    fun compactExtent(): Pair<Float, Float> {
        if (tucked) return 0f to 0f
        if (recSince > 0) {
            sizes()
            return gap() + 10 * d + minute.measureText("00:00") + 12 * d to gap() + 8 * d
        }
        // The always-on capsule: just a ring round the camera and a grey dot.
        if (idle) {
            // Smallest: a ring round the camera and the dot. Largest: as wide as the match notch, empty.
            val r = compactHeight() / 2f
            val f = (idleK - 0.5f).coerceIn(0f, 1f)
            val lMax = min(leftMax, 150 * d)
            val rMax = min(rightMax, 150 * d)
            var left = r + max(0f, lMax - r) * f
            var right = r + 12 * d + max(0f, rMax - r - 12 * d) * f
            // Room for what it shows (the date / prayer / match on the left, the temperature on the right).
            sizes()
            if (idleText.isNotEmpty()) left = max(left, min(lMax, gap() + line.measureText(idleText) + 12 * d))
            if (idleTemp.isNotEmpty()) right = max(right, gap() + minute.measureText(idleTemp) + 12 * d)
            return left to right
        }
        return leftWidth(leftLevel()) to rightWidth(rightLevel())
    }

    /** The tucked mark: a window right of the camera, wide enough to swipe on. */
    fun tuckWidth(): Int = (40 * d).toInt()

    fun bigWidth(screenW: Int): Int {
        val full = min(screenW - (24 * resources.displayMetrics.density).toInt(), (360 * d).toInt())
        val b = burst ?: return full
        if (!isInline(b)) return full
        if (b.kind == "news") return min(full, (330 * d).toInt())
        // Match events: just wide enough for the word on the left, symmetric round the camera.
        sizes()
        val (lw, rw) = compactExtent()
        val need = inlineWordRight() + inlineWordWidth(b) + 12 * d
        return min(full, (2 * maxOf(lw, rw, need)).toInt())
    }

    private val INLINE = setOf("goal", "red", "var", "pen", "whistle", "lineups", "notice", "news")

    /** Played inside the notch (not opened yet). */
    fun isInline(b: Burst?) = b != null && inlineEvents && !b.expanded && b.kind in INLINE

    /** Distance from the camera to the right edge of the word (left of the home crest). */
    private fun inlineWordRight(): Float {
        val m = match ?: return gap()
        return gap() + score.measureText(scoreText(m, true)) + 5 * d + baseCrest() + 7 * d
    }

    private fun inlineWords(b: Burst): List<String> {
        val first = b.title
        val who = b.sub.substringBefore(" • ").substringBefore("•").trim()
        return if (who.isNotEmpty() && b.kind != "whistle" && b.kind != "lineups") listOf(first, who) else listOf(first)
    }

    private fun inlineWordWidth(b: Burst): Float {
        val old = title.textSize
        title.textSize = 12.5f * d
        val w = inlineWords(b).maxOf { title.measureText(it) }
        title.textSize = old
        return min(w, 120 * d)
    }

    private fun top() = compactHeight() / 2f + camR + 6 * d

    private fun goals(home: Boolean) = match?.events?.filter { it.kind == "goal" && it.home == home } ?: emptyList()

    private fun infoLines(): Int = min(4, max(goals(true).size, goals(false).size).let { if (it > 3) 4 else it })

    /** Width the headline gets in the news view. */
    private fun newsAvail(): Float = bigWidth(resources.displayMetrics.widthPixels) - 28 * d - 30 * d - 10 * d

    /** Splits text into lines that fit [avail], breaking at spaces; the last line is cut with "…". */
    private fun wrap(text: String, p: Paint, avail: Float, maxLines: Int): List<String> {
        val out = ArrayList<String>()
        var rest = text.trim()
        while (rest.isNotEmpty() && out.size < maxLines) {
            if (out.size == maxLines - 1) {
                out += TextUtils.ellipsize(rest, p as TextPaint, avail, TextUtils.TruncateAt.END).toString()
                break
            }
            var n = p.breakText(rest, true, avail, null)
            if (n <= 0) break
            if (n < rest.length) {
                val sp = rest.lastIndexOf(' ', n)
                if (sp > n / 2) n = sp
            }
            out += rest.substring(0, n).trim()
            rest = rest.substring(n).trim()
        }
        return out
    }

    private fun newsLines(b: Burst): List<String> {
        val old = line.textSize
        line.textSize = 12.5f * d
        val l = wrap(b.sub, line, max(40f, newsAvail()), if (b.expanded) 5 else if (b.kind == "phone") 2 else 1)
        line.textSize = old
        return l
    }

    fun bigHeight(): Int = when {
        isInline(burst) -> compactHeight()
        info && burst == null -> (top() + 40 * d + infoLines() * 15 * d + statsHeight() + 6 * d + HINT_ROW * d).toInt()
        burst?.kind == "shortcuts" -> (top() + 6 * d + 2 * SLOT_ROW * d + (if (burst?.sub.isNullOrBlank()) 0f else 40 * d) + 8 * d).toInt()
        burst?.kind == "news" || burst?.kind == "phone" ->
            (top() + 22 * d + max(1, newsLines(burst!!).size) * 16 * d + 8 * d).toInt()
        burst?.kind == "menu" -> (top() + (burst?.items?.size ?: 3) * MENU_ROW * d + 8 * d).toInt()
        burst?.kind == "stats" -> (top() + max(1, min(4, burst?.stats?.size ?: 1)) * 30 * d + 12 * d).toInt()
        else -> (top() + 52 * d).toInt()
    }

    private val MENU_ROW = 40f
    private val SLOT_ROW = 64f
    /** The opened match view ends with "⌄ Shortcuts" (tap it, or pull the view down). */
    private val HINT_ROW = 22f

    private fun statsRows() = infoStats?.take(3).orEmpty()
    private fun statsHeight() = if (statsRows().isEmpty()) 0f else 12 * d + statsRows().size * 28 * d

    /** Which shortcut slot (0..7) is at view point (x, y), -1 for none; 100 = the next-match line. */
    private fun slotAt(x: Float, y: Float): Int {
        val bw = bigWidth(resources.displayMetrics.widthPixels).toFloat()
        val l = camX - bw / 2 + 14 * d
        val cw = (bw - 28 * d) / 4
        val y0 = top() + 6 * d
        val row = ((y - y0) / (SLOT_ROW * d)).toInt()
        if (y >= y0 + 2 * SLOT_ROW * d) return if (burst?.sub.isNullOrBlank()) -1 else 100
        if (y < y0 || row !in 0..1) return -1
        val col = ((x - l) / cw).toInt()
        if (col !in 0..3) return -1
        return row * 4 + col
    }

    /* ---------- touch: tap / swipe ---------- */

    private var downX = 0f
    private var downY = 0f

    @SuppressLint("ClickableViewAccessibility")
    private var pressed = false
    private var longDone = false
    private var downLX = 0f
    private var downLY = 0f

    private val longPress = Runnable {
        if (pressed) {
            longDone = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            if (burst?.kind == "shortcuts" && t > 0.9f) {
                val i = slotAt(downLX, downLY)
                if (i in 0..7) gestures.slotLongPress(i)
            } else {
                gestures.longPress()
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val dpx = resources.displayMetrics.density
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downLX = e.x
                downLY = e.y
                downX = e.rawX
                downY = e.rawY
                pressed = true
                longDone = false
                postDelayed(longPress, 500)
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.rawX - downX) > 12 * dpx || abs(e.rawY - downY) > 12 * dpx) {
                    pressed = false
                    removeCallbacks(longPress)
                }
            }
            MotionEvent.ACTION_UP -> {
                pressed = false
                removeCallbacks(longPress)
                if (longDone) return true
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                val far = 36 * dpx
                when {
                    abs(dx) > far && abs(dx) > abs(dy) -> gestures.swipe(dx > 0)
                    abs(dy) > far && abs(dy) > abs(dx) -> gestures.pull(dy > 0)
                    abs(dx) < 12 * dpx && abs(dy) < 12 * dpx -> {
                        val b = burst
                        if (b?.kind == "shortcuts" && t > 0.9f) {
                            val i = slotAt(e.x, e.y)
                            if (i >= 0) gestures.menuPick(i) else gestures.tap()
                        } else if (b == null && info && t > 0.9f && e.y > bigHeight() - HINT_ROW * d) {
                            gestures.shortcuts()
                        } else if (b?.kind == "menu" && t > 0.9f) {
                            val i = ((e.y - top() - 4 * d) / (MENU_ROW * d)).toInt()
                            if (i in b.items.indices) gestures.menuPick(i) else gestures.tap()
                        } else {
                            gestures.tap()
                        }
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                removeCallbacks(longPress)
            }
        }
        return true
    }

    /* ---------- drawing ---------- */

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    override fun onDraw(c: Canvas) {
        sizes()
        if (recSince > 0 && burst == null && !info) {
            drawRec(c)
            return
        }
        if (idle && burst == null && !info && t == 0f && !tucked) {
            drawIdle(c)
            return
        }
        if (tucked && burst == null && !info) {
            drawTuck(c)
            return
        }
        if (circle && burst == null && t == 0f) {
            drawCircle(c)
            return
        }
        val m = match ?: return
        if (isInline(burst)) {
            drawInline(c, m, burst!!)
            return
        }
        val ch = compactHeight().toFloat()
        val (lw, rw) = compactExtent()
        val bw = bigWidth(resources.displayMetrics.widthPixels).toFloat()
        val bh = bigHeight().toFloat()
        val l = lerp(camX - lw, camX - bw / 2, t)
        val r = lerp(camX + rw, camX + bw / 2, t)
        val h = lerp(ch, bh, t)
        val radius = lerp(ch / 2, 24 * d, t)
        val b = burst
        val age = if (b != null) System.currentTimeMillis() - b.startedAt else 0L
        // A red card shakes the notch for a moment.
        val shake = if (b?.kind == "red" && age < 450) (4 * d * sin(age / 22.0) * kotlin.math.exp(-age / 180.0)).toFloat() else 0f
        c.save()
        c.translate(shake, 0f)
        rect.set(l, 0f, r, h)
        c.drawRoundRect(rect, radius, radius, bg)
        // A goal for: the notch flashes in the team colour.
        // Goal for (made for the phone's small pill): a band of light in the team colour sweeps
        // across twice, softly, instead of the desktop's rings and flash.
        if (b != null && b.kind == "goal" && !b.against && age < 1800 && t > 0.3f && !calm) {
            val p = (age % 900) / 900f
            val w = rect.width()
            val bandX = rect.left - w * 0.4f + p * w * 1.8f
            val col = b.color and 0x00FFFFFF
            val strength = if (b.neutral) 0x40 else 0x70
            shine.shader = android.graphics.LinearGradient(
                bandX - w * 0.22f, 0f, bandX + w * 0.22f, 0f,
                intArrayOf(col, (strength shl 24) or col, col), null, android.graphics.Shader.TileMode.CLAMP,
            )
            shine.alpha = (255 * min(1f, t)).toInt()
            c.drawRoundRect(rect, radius, radius, shine)
            postInvalidateDelayed(16)
        }

        val accent = b?.color ?: if (info) 0xFF3F3F46.toInt() else null
        if (accent != null && t > 0.05f) {
            border.color = accent
            border.strokeWidth = 1.5f * d
            // Whistle and goal pulse a little.
            val pulse = if (b != null && b.kind == "whistle") 0.65f + 0.35f * abs(sin(System.currentTimeMillis() / 220.0)).toFloat() else 1f
            border.alpha = (min(1f, t) * 220 * pulse).toInt()
            rect.inset(border.strokeWidth / 2, border.strokeWidth / 2)
            c.drawRoundRect(rect, radius, radius, border)
            if (b != null) postInvalidateOnAnimation()
        }

        val compactAlpha = (1f - t * 2.5f).coerceIn(0f, 1f)
        if (compactAlpha > 0f) drawCompact(c, m, ch, compactAlpha)
        val bigAlpha = ((t - 0.5f) * 2f).coerceIn(0f, 1f)
        if (bigAlpha > 0f) {
            if (b?.kind == "menu") drawMenu(c, b, camX - bw / 2, camX + bw / 2, bigAlpha)
            else if (b?.kind == "stats") drawStats(c, m, b, camX - bw / 2, camX + bw / 2, bigAlpha)
            else if (b?.kind == "shortcuts") drawShortcuts(c, b, camX - bw / 2, camX + bw / 2, bigAlpha)
            else if (b != null) drawBurst(c, m, b, camX - bw / 2, camX + bw / 2, h, bigAlpha)
            else if (info) {
                drawInfo(c, m, camX - bw / 2, camX + bw / 2, bigAlpha)
                drawHint(c, camX - bw / 2, camX + bw / 2, bigHeight().toFloat(), bigAlpha)
            }
        }
        c.restore()
    }

    /** The small circle beside Xiaomi's island: the score, ringed by the live colour. */
    private fun drawCircle(c: Canvas) {
        val m = match ?: return
        val size = compactHeight().toFloat()
        val r = size / 2
        c.drawCircle(r, r, r, bg)
        if (idle) {
            // The always-on notch beside the phone's pill: a black circle with the grey dot.
            dot.color = 0xFF6B7280.toInt()
            dot.alpha = 255
            c.drawCircle(r, r, 2.5f * d, dot)
            return
        }
        val (color, breathe) = when {
            m.state == Api.POST -> 0xFF6B7280.toInt() to false
            m.state == Api.PRE || m.phase == "break" -> 0xFFFBBF24.toInt() to false
            m.phase == "var" -> 0xFF60A5FA.toInt() to true
            else -> 0xFF34D399.toInt() to true
        }
        val level = if (breathe) 0.45f + 0.55f * ((sin(System.currentTimeMillis() / 450.0) + 1) / 2).toFloat() else 1f
        stroke.color = color
        stroke.alpha = (255 * level).toInt()
        stroke.strokeWidth = 1.6f * d
        c.drawCircle(r, r, r - 1.6f * d, stroke)
        val txt = if (m.state == Api.PRE) clock.filter { it.isDigit() }.ifBlank { "•" } else "${m.home.score}-${m.away.score}"
        val old = score.textSize
        score.textSize = (if (txt.length > 3) 8.5f else 10.5f) * d
        score.color = Color.WHITE
        score.alpha = 255
        score.textAlign = Paint.Align.CENTER
        c.drawText(txt, r, baseline(score, r), score)
        score.textAlign = Paint.Align.LEFT
        score.textSize = old
        if (breathe && !calm) postInvalidateDelayed(50)
    }

    /** Always-on, nothing on: a black ring round the camera and a small grey dot. */
    private fun drawIdle(c: Canvas) {
        val ch = compactHeight().toFloat()
        val (lw, rw) = compactExtent()
        rect.set(camX - lw, 0f, camX + rw, ch)
        c.drawRoundRect(rect, ch / 2, ch / 2, bg)
        val now = System.currentTimeMillis()
        if (weather.isNotEmpty() && (weatherAlways || now < weatherUntil) && !calm) {
            c.save()
            path.reset()
            path.addRoundRect(rect, ch / 2, ch / 2, Path.Direction.CW)
            c.clipPath(path)
            drawWeather(c, weather, rect.left, rect.right, ch, now)
            c.restore()
            postInvalidateDelayed(33)
        }
        val cy = ch / 2
        // Left of the camera: the date, the next prayer or the next match (they take turns).
        if (idleText.isNotEmpty()) {
            line.color = 0xFFE5E7EB.toInt()
            line.alpha = 255
            line.textAlign = Paint.Align.RIGHT
            val avail = lw - gap() - 10 * d
            if (avail > 20 * d) c.drawText(TextUtils.ellipsize(idleText, line, avail, TextUtils.TruncateAt.END).toString(), camX - gap(), baseline(line, cy), line)
            line.textAlign = Paint.Align.LEFT
            line.color = 0xFFC8C8C8.toInt()
        }
        // Right: the temperature, else the grey dot.
        if (idleTemp.isNotEmpty()) {
            minute.color = Color.WHITE
            minute.alpha = 255
            minute.textAlign = Paint.Align.LEFT
            c.drawText(idleTemp, camX + gap(), baseline(minute, cy), minute)
        } else {
            dot.color = 0xFF6B7280.toInt()
            dot.alpha = 255
            c.drawCircle(camX + rw - 8 * d, cy, 2.5f * d, dot)
        }
    }

    private val path = Path()

    /** Weather inside the pill: drawn from the clock alone (no particle state to keep). */
    private fun drawWeather(c: Canvas, kind: String, l: Float, r: Float, h: Float, now: Long) {
        val w = r - l
        fun fr(x: Double) = (x - kotlin.math.floor(x)).toFloat()
        when (kind) {
            "rain", "storm" -> {
                val storm = kind == "storm"
                if (storm) {
                    // A flash every few seconds.
                    val ph = (now % 4200) / 4200f
                    if (ph < 0.06f) {
                        dot.color = 0xFFDCE6FF.toInt()
                        dot.alpha = ((0.06f - ph) / 0.06f * 140).toInt()
                        c.drawRect(l, 0f, r, h, dot)
                    }
                }
                stroke.color = if (storm) 0xFF96AADC.toInt() else 0xFF8CB4FF.toInt()
                stroke.strokeWidth = 1.2f * d
                stroke.alpha = 190
                val n = if (storm) 60 else 40
                val v = if (storm) 0.26 else 0.17
                for (i in 0 until n) {
                    val y = fr(i * 0.618 + now * v / h * d / 10) * (h + 10 * d) - 5 * d
                    val x = l + fr(i * 0.381 + now * (if (storm) 0.00012 else 0.00004)) * (w + 20 * d) - (y / h) * 6 * d
                    c.drawLine(x, y, x + (if (storm) 3.5f else 1.6f) * d, y - 6 * d, stroke)
                }
            }
            "snow" -> {
                dot.color = Color.WHITE
                dot.alpha = 220
                for (i in 0 until 34) {
                    val y = fr(i * 0.618 + now * 0.00025) * (h + 6 * d) - 3 * d
                    val x = l + fr(i * 0.381) * w + sin(now / 900.0 + i).toFloat() * 3 * d
                    c.drawCircle(x, y, (0.8f + (i % 3) * 0.5f) * d, dot)
                }
            }
            "sun" -> {
                val x = l + h * 0.9f
                val pulse = 0.85f + 0.15f * sin(now / 700.0).toFloat()
                shine.shader = android.graphics.RadialGradient(
                    x, h / 2, max(1f, 70 * d * pulse),
                    intArrayOf(0xD9FFC44D.toInt(), 0x47FF9628, 0x00FF9628), floatArrayOf(0f, 0.35f, 1f), android.graphics.Shader.TileMode.CLAMP,
                )
                shine.alpha = 255
                c.drawRect(l, 0f, r, h, shine)
                stroke.color = 0xFFFFD278.toInt()
                stroke.strokeWidth = 1.4f * d
                stroke.alpha = 140
                c.save()
                c.rotate((now / 4000f * 57.3f) % 360f, x, h / 2)
                for (k in 0 until 8) {
                    c.rotate(45f, x, h / 2)
                    c.drawLine(x + 9 * d, h / 2, x + 13 * d, h / 2, stroke)
                }
                c.restore()
                dot.color = 0xFFFFCF5A.toInt()
                dot.alpha = 255
                c.drawCircle(x, h / 2, 5.5f * d, dot)
            }
            "clouds" -> {
                dot.color = 0xFFC8D2E6.toInt()
                dot.alpha = 95
                for (i in 0 until 4) {
                    val x = l + fr(i * 0.27 + now * 0.000025) * (w + 60 * d) - 30 * d
                    val sc = 0.8f + (i % 2) * 0.4f
                    c.drawCircle(x, h / 2 + 2 * d * sc, 9 * d * sc, dot)
                    c.drawCircle(x + 9 * d * sc, h / 2 - 2 * d * sc, 11 * d * sc, dot)
                    c.drawCircle(x + 20 * d * sc, h / 2 + 2 * d * sc, 8 * d * sc, dot)
                }
            }
            "wind" -> {
                stroke.strokeWidth = 1.3f * d
                stroke.color = 0xFFE6F0FF.toInt()
                for (i in 0 until 12) {
                    val len = (14 + (i * 7) % 18) * d
                    val x = l + fr(i * 0.381 + now * 0.0006) * (w + len) 
                    val y = h * (0.2f + fr(i * 0.618) * 0.6f)
                    stroke.alpha = 150
                    c.drawLine(x - len, y, x, y, stroke)
                }
            }
            "fog" -> {
                for (k in 0 until 3) {
                    val off = fr(now / (30000.0 + k * 9000)) * w
                    shine.shader = android.graphics.LinearGradient(
                        l - w + off, 0f, l + off, 0f,
                        intArrayOf(0x00DCE1EB, 0x40DCE1EB + k * 0x0D000000, 0x00DCE1EB), null, android.graphics.Shader.TileMode.MIRROR,
                    )
                    shine.alpha = 255
                    c.drawRect(l, h * (0.1f + k * 0.27f), r, h * (0.1f + k * 0.27f) + h * 0.4f, shine)
                }
            }
        }
    }

    /**
     * An event played inside the notch, at the notch's height (a tap opens it big).
     * Goal: the team colour washes across and back, a light runs round the edge, "هدف!" writes
     * itself, then the scorer's name. News: the notch flips over to the headline.
     */
    private fun drawInline(c: Canvas, m: Match, b: Burst) {
        val ch = compactHeight().toFloat()
        val (lw, rw) = compactExtent()
        val bw = bigWidth(resources.displayMetrics.widthPixels).toFloat()
        val l = lerp(camX - lw, camX - bw / 2, t)
        val r = lerp(camX + rw, camX + bw / 2, t)
        val age = System.currentTimeMillis() - b.startedAt
        val cy = ch / 2
        val shake = if (b.kind == "red" && age < 450) (4 * d * sin(age / 22.0) * kotlin.math.exp(-age / 180.0)).toFloat() else 0f
        c.save()
        c.translate(shake, 0f)
        rect.set(l, 0f, r, ch)

        if (b.kind == "news") {
            // The notch flips over: the match on the way in, the headline once it has turned.
            val flip = abs(kotlin.math.cos(t * Math.PI)).toFloat().coerceAtLeast(0.04f)
            c.scale(1f, flip, 0f, cy)
            c.drawRoundRect(rect, ch / 2, ch / 2, bg)
            if (t < 0.5f) {
                // The front: the match if there is one (news on a quiet notch has a blank front).
                if (m.id >= 0) drawCompact(c, m, ch, 1f)
            } else {
                drawInlineNews(c, b, l, r, ch)
            }
            c.restore()
            return
        }

        c.drawRoundRect(rect, ch / 2, ch / 2, bg)
        val goalFor = b.kind == "goal" && !b.against
        // The colour wave (goals for; softer for a neutral pinned match).
        if (goalFor && !calm && age < 1500) {
            val fill = if (age < 650) ease(age / 650f) else (1f - (age - 900) / 600f).coerceIn(0f, 1f)
            if (fill > 0f) {
                c.save()
                path.reset()
                path.addRoundRect(rect, ch / 2, ch / 2, Path.Direction.CW)
                c.clipPath(path)
                val edge = l + (r - l) * fill
                val col = b.color and 0x00FFFFFF
                val a = if (b.neutral) 0x99 else 0xFF
                shine.shader = android.graphics.LinearGradient(edge - 40 * d, 0f, edge, 0f, (a shl 24) or col, col, android.graphics.Shader.TileMode.CLAMP)
                shine.alpha = 255
                dot.color = (a shl 24) or col
                dot.alpha = a
                c.drawRect(l, 0f, max(l, edge - 40 * d), ch, dot)
                c.drawRect(edge - 40 * d, 0f, edge, ch, shine)
                c.restore()
                postInvalidateOnAnimation()
            }
        }
        // The match itself stays readable underneath.
        drawCompact(c, m, ch, 1f)

        // The word over the minute: "هدف!" (or the card, VAR…), then who it was.
        val words = inlineWords(b)
        val word = if (words.size > 1 && age > 2600) words[1] else words[0]
        val wordRight = camX - inlineWordRight()
        val reveal = ease((age - 200) / 500f)
        if (reveal > 0f) {
            bg.alpha = 255
            val waveOn = goalFor && age in 200..1300
            dot.color = if (waveOn) b.color else Color.BLACK
            dot.alpha = 255
            c.drawRect(l + ch / 2, 2 * d, wordRight + 2 * d, ch - 2 * d, dot)
            val old = title.textSize
            title.textSize = 12.5f * d
            title.color = when {
                waveOn -> Color.WHITE
                b.against -> 0xFFD4D4D8.toInt()
                else -> b.color
            }
            title.alpha = 255
            title.textAlign = Paint.Align.RIGHT
            val avail = wordRight - (l + ch / 2)
            val text = TextUtils.ellipsize(word, title, max(10f, avail), TextUtils.TruncateAt.END).toString()
            val tw = title.measureText(text)
            // Written from the right, like Arabic handwriting.
            c.save()
            c.clipRect(wordRight - tw * reveal - 2 * d, 0f, wordRight + 2 * d, ch)
            c.drawText(text, wordRight, baseline(title, cy), title)
            c.restore()
            title.textAlign = Paint.Align.CENTER
            title.textSize = old
            if (age < 800) postInvalidateOnAnimation()
        }

        // A light runs round the edge (goal: twice; other events: once, in their colour).
        val runFor = if (goalFor) 2400L else 1300L
        val runFrom = if (goalFor) 900L else 100L
        if (!calm && age in runFrom..(runFrom + runFor)) {
            val p = (age - runFrom) / 1200f
            val inset = 1.2f * d
            rect.inset(inset, inset)
            path.reset()
            path.addRoundRect(rect, ch / 2 - inset, ch / 2 - inset, Path.Direction.CW)
            val per = 2 * (rect.width() - rect.height()) + Math.PI.toFloat() * rect.height()
            border.pathEffect = android.graphics.DashPathEffect(floatArrayOf(per * 0.22f, per * 0.78f), -per * p)
            border.color = b.color
            border.strokeWidth = 2.2f * d
            border.alpha = (255 * ((runFrom + runFor - age) / 400f).coerceIn(0f, 1f)).toInt()
            border.setShadowLayer(8 * d, 0f, 0f, b.color)
            c.drawPath(path, border)
            border.pathEffect = null
            border.clearShadowLayer()
            postInvalidateOnAnimation()
        }
        c.restore()
    }

    private fun ease(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return 1f - (1f - t) * (1f - t) * (1f - t)
    }

    /** The headline inside the notch: crest, "خبر", one line. */
    private fun drawInlineNews(c: Canvas, b: Burst, l: Float, r: Float, ch: Float) {
        val cy = ch / 2
        val crest = ch - 12 * d
        val icon = b.icon
        if (icon != null) {
            rect.set(l + ch / 2 - 2 * d, cy - crest / 2, l + ch / 2 - 2 * d + crest, cy + crest / 2)
            c.drawBitmap(icon, null, rect, bmp)
        } else if (b.logo != null) {
            drawCrest(c, b.logo, l + ch / 2 - 2 * d, cy - crest / 2, crest, 1f)
        }
        val labelX = l + ch / 2 + crest + 4 * d
        small.color = 0xFF60A5FA.toInt()
        small.alpha = 255
        small.textAlign = Paint.Align.LEFT
        val label = if (L.ar) "خبر" else "News"
        c.drawText(label, labelX, baseline(small, cy), small)
        small.color = 0xFF9CA3AF.toInt()
        val old = line.textSize
        line.textSize = 11.5f * d
        line.color = Color.WHITE
        line.alpha = 255
        // The headline sits right of the camera hole, so no letter hides behind it.
        val textL = max(labelX + small.measureText(label) + 6 * d, camX + gap())
        val textR = r - ch / 2
        val head = TextUtils.ellipsize(b.sub, line, max(10f, textR - textL), TextUtils.TruncateAt.END).toString()
        line.textAlign = if (L.ar) Paint.Align.RIGHT else Paint.Align.LEFT
        c.drawText(head, if (L.ar) textR else textL, baseline(line, cy), line)
        line.textAlign = Paint.Align.LEFT
        line.textSize = old
        line.color = 0xFFC8C8C8.toInt()
    }

    /** Recording the screen: a red dot breathing and the time. */
    private fun drawRec(c: Canvas) {
        val ch = compactHeight().toFloat()
        val (lw, rw) = compactExtent()
        rect.set(camX - lw, 0f, camX + rw, ch)
        c.drawRoundRect(rect, ch / 2, ch / 2, bg)
        val secs = ((System.currentTimeMillis() - recSince) / 1000).toInt()
        val label = String.format(java.util.Locale.US, "%02d:%02d", secs / 60, secs % 60)
        minute.color = Color.WHITE
        minute.alpha = 255
        val x = camX - gap() - minute.measureText(label)
        c.drawText(label, x, baseline(minute, ch / 2), minute)
        dot.color = 0xFFEF4444.toInt()
        dot.alpha = (255 * (0.5f + 0.5f * abs(sin(System.currentTimeMillis() / 400.0)).toFloat())).toInt()
        c.drawCircle(x - 8 * d, ch / 2, 3.5f * d, dot)
        postInvalidateDelayed(100)
    }

    /** The shortcut grid: 8 slots (empty ones show "+"), then the next match. */
    private fun drawShortcuts(c: Canvas, b: Burst, l: Float, r: Float, alpha: Float) {
        val a = (alpha * 255).toInt()
        val left = l + 14 * d
        val cw = (r - l - 28 * d) / 4
        val y0 = top() + 6 * d
        val icon = 40 * d
        val oldTitle = title.textSize
        for (i in 0 until 8) {
            val cx = left + cw * (i % 4) + cw / 2
            val cy = y0 + (i / 4) * SLOT_ROW * d + icon / 2
            val label = b.items.getOrNull(i).orEmpty()
            dot.color = if (label.isEmpty()) 0xFF15171E.toInt() else 0xFF1C1F28.toInt()
            dot.alpha = a
            rect.set(cx - icon / 2, cy - icon / 2, cx + icon / 2, cy + icon / 2)
            c.drawRoundRect(rect, 13 * d, 13 * d, dot)
            val bm = b.icons.getOrNull(i)
            if (bm != null) {
                bmp.alpha = a
                rect.inset(8 * d, 8 * d)
                c.drawBitmap(bm, null, rect, bmp)
            } else {
                val g = if (label.isEmpty()) "+" else b.glyphs.getOrNull(i).orEmpty()
                title.textSize = (if (label.isEmpty()) 20f else 18f) * d
                title.color = if (label.isEmpty()) 0xFF71717A.toInt() else Color.WHITE
                title.alpha = a
                c.drawText(g, cx, baseline(title, cy), title)
            }
            if (label.isNotEmpty()) {
                sub.textSize = 9.5f * d
                sub.alpha = a
                c.drawText(TextUtils.ellipsize(label, sub, cw - 4 * d, TextUtils.TruncateAt.END).toString(), cx, cy + icon / 2 + 4 * d - sub.ascent(), sub)
                sub.textSize = 12f * d
            }
        }
        title.textSize = oldTitle
        if (b.sub.isNotBlank()) {
            val y = y0 + 2 * SLOT_ROW * d + 4 * d
            dot.color = 0xFF15171E.toInt()
            dot.alpha = a
            rect.set(left, y, r - 14 * d, y + 30 * d)
            c.drawRoundRect(rect, 12 * d, 12 * d, dot)
            line.color = Color.WHITE
            line.alpha = a
            line.textAlign = Paint.Align.CENTER
            c.drawText(TextUtils.ellipsize(b.sub, line, r - l - 48 * d, TextUtils.TruncateAt.END).toString(), (l + r) / 2, baseline(line, y + 15 * d), line)
            line.textAlign = Paint.Align.LEFT
            line.color = 0xFFC8C8C8.toInt()
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
        if (idle || recSince > 0) {
            // The always-on ring (or the recording pill) while it shrinks back: just the grey dot.
            dot.color = 0xFF6B7280.toInt()
            dot.alpha = (alpha * 255).toInt()
            c.drawCircle(camX + gap() + 6 * d, ch / 2, 2.5f * d, dot)
            return
        }
        val a = (alpha * (if (idle) 0.85f else 1f) * 255).toInt()
        val al = a / 255f
        val lk = leftLevel()
        val rk = rightLevel()
        val cy = ch / 2
        score.alpha = a

        // Left of the camera, outward: home score, crest (red cards on its corner), name (full screen), minute (+ added).
        var x = camX - gap()
        val hs = scoreText(m, true)
        x -= score.measureText(hs)
        drawScore(c, hs, x, cy, m.home.color, homeFlash, a)
        if (lk < 4) {
            val crest = crestAt(lk)
            x -= 5 * d + crest
            drawCrest(c, m.home.logo, x, cy - crest / 2, crest, al)
            if (reds(true) > 0) drawRedCards(c, reds(true), x - 2 * d, cy + crest / 2 - 5 * d, al)
            if (nameWidth(true) > 0f) {
                val nw = nameWidth(true) - 5 * d
                name.alpha = a
                name.textAlign = Paint.Align.LEFT
                x -= 5 * d + nw
                c.drawText(TextUtils.ellipsize(teamName(true), name, nw, TextUtils.TruncateAt.END).toString(), x, baseline(name, cy), name)
            }
        } else if (reds(true) > 0) {
            x -= redsWidth(true)
            drawRedCards(c, reds(true), x, cy - 2.5f * d, al)
        }
        x -= max(0f, stretch)
        if (lk < 3) {
            val label = clockLabel(m)
            minute.color = minuteColor(m)
            minute.alpha = a
            if (m.phase == "var" && !calm) {
                // "VAR" blinks while the referee is at the screen.
                minute.alpha = (a * (0.35f + 0.65f * abs(sin(System.currentTimeMillis() / 300.0)).toFloat())).toInt()
                postInvalidateDelayed(50)
            }
            x -= 6 * d + minute.measureText(label)
            c.drawText(label, x, baseline(minute, cy), minute)
            if (lk < 2 && added.isNotEmpty()) {
                small.alpha = a
                x -= 2 * d + small.measureText(added)
                c.drawText(added, x, cy - 1 * d, small)
            }
        }

        if (landscape && m.state != Api.PRE) {
            minute.color = 0xFF9CA3AF.toInt()
            minute.alpha = a
            minute.textAlign = Paint.Align.CENTER
            c.drawText("–", camX, baseline(minute, cy), minute)
            minute.textAlign = Paint.Align.LEFT
        }

        // Right of the camera, outward: away score, crest (red cards on its corner), name, the live dot.
        x = camX + gap()
        val aw = scoreText(m, false)
        drawScore(c, aw, x, cy, m.away.color, awayFlash, a)
        x += score.measureText(aw)
        if (rk < 2) {
            val crest = crestAt(rk)
            x += 5 * d
            drawCrest(c, m.away.logo, x, cy - crest / 2, crest, al)
            x += crest
            if (reds(false) > 0) {
                val n = min(reds(false), 3)
                drawRedCards(c, reds(false), x - n * 4 * d + 2 * d, cy + crest / 2 - 5 * d, al)
            }
            if (nameWidth(false) > 0f) {
                val nw = nameWidth(false) - 5 * d
                name.alpha = a
                name.textAlign = Paint.Align.LEFT
                c.drawText(TextUtils.ellipsize(teamName(false), name, nw, TextUtils.TruncateAt.END).toString(), x + 5 * d, baseline(name, cy), name)
                x += 5 * d + nw
            }
        } else if (reds(false) > 0) {
            drawRedCards(c, reds(false), x + 3 * d, cy - 2.5f * d, al)
            x += redsWidth(false)
        }

        // The live dot: breathes while the ball is rolling, amber at a break, grey when over.
        val now = System.currentTimeMillis()
        val (color, breathe) = when {
            idle -> 0xFF6B7280.toInt() to false
            m.state == Api.POST -> 0xFF6B7280.toInt() to false
            m.state == Api.PRE -> 0xFFFBBF24.toInt() to false
            m.phase == "break" -> 0xFFFBBF24.toInt() to false
            m.phase == "var" -> 0xFF60A5FA.toInt() to true
            else -> 0xFF34D399.toInt() to true
        }
        val level = if (breathe && !calm) 0.35f + 0.65f * ((sin(now / 450.0) + 1) / 2).toFloat() else 1f
        dot.color = color
        dot.alpha = (a * level).toInt()
        val dx = x + max(0f, stretch) + 6 * d + 3 * d
        c.drawCircle(dx, cy, 3 * d, dot)
        if (breathe && !calm) {
            dot.alpha = (a * level * 0.25f).toInt()
            c.drawCircle(dx, cy, 5.5f * d, dot)
            postInvalidateDelayed(50)
        }
        // Another followed match is live: a small white dot beside it.
        if (others > 0) {
            dot.color = Color.WHITE
            dot.alpha = (a * 0.7f).toInt()
            c.drawCircle(dx + 7 * d, cy, 2 * d, dot)
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
        val age = System.currentTimeMillis() - b.startedAt
        val top = top()
        val bottom = h - 6 * d
        val mid = (top + bottom) / 2
        val left = l + 14 * d
        val right = r - 14 * d
        val crest = 34 * d
        val cx = left + crest / 2

        if (b.kind == "news" || b.kind == "phone") {
            drawNews(c, b, left, right, top, bottom, alpha)
            return
        }

        // VAR: a scan line sweeps across.
        if (b.kind == "var") {
            val p = (age % 1300) / 1300f
            val x = l + p * (r - l)
            stroke.color = b.color
            stroke.strokeWidth = 2f * d
            for (k in 0..5) {
                stroke.alpha = (a * (0.7f - k * 0.12f)).toInt().coerceAtLeast(0)
                val xx = x - k * 5 * d
                if (xx > l + 8 * d) c.drawLine(xx, top - 2 * d, xx, bottom + 2 * d, stroke)
            }
        }

        if (b.kind == "whistle") {
            drawWhistle(c, cx, mid, b.color, alpha)
        } else {
            // The crest pulses once on a goal (up and back in half a second).
            val bounce = if (b.kind == "goal" && !b.against && age < 500) 1f + 0.14f * sin(age / 500.0 * Math.PI).toFloat() else 1f
            val sz = crest * bounce
            drawCrest(c, if (b.home) m.home.logo else m.away.logo, cx - sz / 2, mid - sz / 2, sz, alpha)
        }
        // Red card: a card flips in over the crest's corner.
        if (b.kind == "red") {
            val f = (age / 380f).coerceIn(0f, 1f)
            val w = 11 * d
            val hh = 15 * d
            val ccx = left + crest - 2 * d
            val ccy = mid + crest / 2 - hh / 2
            c.save()
            c.scale(kotlin.math.abs(kotlin.math.cos((1 - f) * Math.PI / 2).toFloat()).coerceAtLeast(0.05f), 1f, ccx, ccy)
            c.rotate(12f, ccx, ccy)
            dot.color = 0xFFEF4444.toInt()
            dot.alpha = a
            rect.set(ccx - w / 2, ccy - hh / 2, ccx + w / 2, ccy + hh / 2)
            c.drawRoundRect(rect, 2 * d, 2 * d, dot)
            c.restore()
        }

        // The score pops when it has just changed.
        val sc = bigScore(m)
        score.alpha = a
        val big = score.textSize
        score.textSize = 18f * d
        val sw = score.measureText(sc)
        score.textAlign = Paint.Align.CENTER
        if (b.kind == "goal" && age < 350) {
            // The score flips over to the new one.
            val f = age / 350f
            c.save()
            c.scale(1f, abs(kotlin.math.cos((1 - f) * Math.PI / 2)).toFloat().coerceAtLeast(0.05f), right - sw / 2, mid)
            c.drawText(sc, right - sw / 2, baseline(score, mid), score)
            c.restore()
            postInvalidateDelayed(16)
        } else {
            c.drawText(sc, right - sw / 2, baseline(score, mid), score)
        }
        score.textAlign = Paint.Align.LEFT
        score.textSize = big

        val textL = left + crest + 10 * d
        val textR = right - sw - 10 * d
        val tx = (textL + textR) / 2
        val avail = max(20f, textR - textL)
        title.color = if (b.against) 0xFFD4D4D8.toInt() else b.color
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

    /** News: the team's crest, "خبر", and the headline on up to two lines. */
    private fun drawNews(c: Canvas, b: Burst, left: Float, right: Float, top: Float, bottom: Float, alpha: Float) {
        val a = (alpha * 255).toInt()
        val crest = 30 * d
        val mid = min((top + bottom) / 2, top + 4 * d + crest / 2)
        if (b.icon != null) {
            bmp.alpha = (alpha * 255).toInt()
            rect.set(left, mid - crest / 2, left + crest, mid + crest / 2)
            c.drawBitmap(b.icon, null, rect, bmp)
        } else {
            b.logo?.let { drawCrest(c, it, left, mid - crest / 2, crest, alpha) }
        }
        val textL = left + crest + 10 * d
        val avail = max(20f, right - textL)
        title.color = b.color
        title.alpha = a
        title.textAlign = Paint.Align.LEFT
        val oldTitle = title.textSize
        title.textSize = 11f * d
        c.drawText(TextUtils.ellipsize(b.title, title, avail, TextUtils.TruncateAt.END).toString(), textL, top + 2 * d - title.ascent(), title)
        title.textSize = oldTitle
        title.textAlign = Paint.Align.CENTER
        // The headline: one line (cut) at first, the whole of it after a tap.
        val lines = newsLines(b)
        line.color = Color.WHITE
        line.alpha = a
        val old = line.textSize
        line.textSize = 12.5f * d
        lines.forEachIndexed { i, s ->
            c.drawText(s, textL, top + 18 * d - line.ascent() + i * 16 * d, line)
        }
        line.textSize = old
        line.color = 0xFFC8C8C8.toInt()
    }

    /** "Salah (P) 45'"; with no name from 365, the team's name and the minute. */
    private fun scorerText(m: Match, e: Ev): String {
        val tag = when {
            e.own -> L.t("own_goal")
            e.pen -> L.t("pen_short")
            else -> ""
        }
        val who = e.player?.takeIf { it.isNotBlank() } ?: m.side(e.home).short
        return "$who$tag ${e.minute}".trim()
    }

    /** After a tap: crest + name each side, the score and minute in the middle, scorers below. */
    private fun drawInfo(c: Canvas, m: Match, l: Float, r: Float, alpha: Float) {
        val a = (alpha * 255).toInt()
        val top = top()
        val left = l + 14 * d
        val right = r - 14 * d
        val crest = 26 * d
        val rowMid = top + 16 * d

        val sc = bigScore(m)
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
            val shown = gs.take(3).map { "⚽ " + scorerText(m, it) }.toMutableList()
            if (gs.size > 3) shown += "+${gs.size - 3}"
            line.textAlign = if (home) Paint.Align.LEFT else Paint.Align.RIGHT
            shown.forEachIndexed { i, s ->
                val y = top + 40 * d + i * 15 * d - line.ascent()
                c.drawText(TextUtils.ellipsize(s, line, colW, TextUtils.TruncateAt.END).toString(), if (home) left else right, y, line)
            }
        }
        line.textAlign = Paint.Align.LEFT

        // Quick stats: the numbers each side, the name in the middle, a bar in the teams' colours.
        val rows = statsRows()
        if (rows.isEmpty()) return
        var y = top + 40 * d + infoLines() * 15 * d + 4 * d
        stroke.color = 0xFF26282F.toInt()
        stroke.strokeWidth = 1 * d
        stroke.alpha = a
        c.drawLine(left, y, right, y, stroke)
        y += 8 * d
        for (row in rows) {
            name.alpha = a
            name.textAlign = Paint.Align.LEFT
            c.drawText(row.home, left, y + 11 * d, name)
            name.textAlign = Paint.Align.RIGHT
            c.drawText(row.away, right, y + 11 * d, name)
            name.textAlign = Paint.Align.LEFT
            line.alpha = a
            line.textAlign = Paint.Align.CENTER
            c.drawText(TextUtils.ellipsize(row.name, line, (right - left) * 0.6f, TextUtils.TruncateAt.END).toString(), (left + right) / 2, y + 11 * d, line)
            line.textAlign = Paint.Align.LEFT
            val split = left + (right - left) * row.homeShare.coerceIn(0.03f, 0.97f)
            dot.color = m.home.color
            dot.alpha = a
            rect.set(left, y + 15 * d, split - 1.5f * d, y + 19 * d)
            c.drawRoundRect(rect, 2 * d, 2 * d, dot)
            dot.color = m.away.color
            dot.alpha = a
            rect.set(split + 1.5f * d, y + 15 * d, right, y + 19 * d)
            c.drawRoundRect(rect, 2 * d, 2 * d, dot)
            y += 28 * d
        }
    }

    /** The bottom row of the opened match view: "⌄ Shortcuts". */
    private fun drawHint(c: Canvas, l: Float, r: Float, bottom: Float, alpha: Float) {
        val a = (alpha * 255).toInt()
        line.color = 0xFF9CA3AF.toInt()
        line.alpha = a
        line.textAlign = Paint.Align.CENTER
        c.drawText("⌄  " + L.t("sc_hint"), (l + r) / 2, bottom - 8 * d, line)
        line.textAlign = Paint.Align.LEFT
        line.color = 0xFFC8C8C8.toInt()
    }

    /** The long-press menu: one row per action, tapped by position. */
    private fun drawMenu(c: Canvas, b: Burst, l: Float, r: Float, alpha: Float) {
        val a = (alpha * 255).toInt()
        val top = top() + 4 * d
        name.alpha = a
        name.textAlign = Paint.Align.CENTER
        b.items.forEachIndexed { i, s ->
            val y0 = top + i * MENU_ROW * d
            if (i > 0) {
                stroke.color = 0xFF2A2D38.toInt()
                stroke.strokeWidth = 1 * d
                stroke.alpha = a
                c.drawLine(l + 18 * d, y0, r - 18 * d, y0, stroke)
            }
            c.drawText(s, (l + r) / 2, baseline(name, y0 + MENU_ROW * d / 2), name)
        }
        name.textAlign = Paint.Align.LEFT
    }

    /** Pulled down: the main numbers, a bar per row in the teams' colours. */
    private fun drawStats(c: Canvas, m: Match, b: Burst, l: Float, r: Float, alpha: Float) {
        val a = (alpha * 255).toInt()
        val top = top() + 4 * d
        val left = l + 18 * d
        val right = r - 18 * d
        val st = b.stats
        line.alpha = a
        if (st == null || st.isEmpty()) {
            line.textAlign = Paint.Align.CENTER
            c.drawText(L.t(if (st == null) "stats_loading" else "stats_none"), (l + r) / 2, top + 18 * d, line)
            line.textAlign = Paint.Align.LEFT
            return
        }
        st.take(4).forEachIndexed { i, row ->
            val y = top + i * 30 * d
            name.alpha = a
            name.textAlign = Paint.Align.LEFT
            c.drawText(row.home, left, y + 12 * d, name)
            name.textAlign = Paint.Align.RIGHT
            c.drawText(row.away, right, y + 12 * d, name)
            line.textAlign = Paint.Align.CENTER
            c.drawText(TextUtils.ellipsize(row.name, line, (right - left) * 0.6f, TextUtils.TruncateAt.END).toString(), (left + right) / 2, y + 12 * d, line)
            line.textAlign = Paint.Align.LEFT
            val split = left + (right - left) * row.homeShare.coerceIn(0.03f, 0.97f)
            dot.color = m.home.color
            dot.alpha = a
            rect.set(left, y + 17 * d, split - 1.5f * d, y + 21 * d)
            c.drawRoundRect(rect, 2 * d, 2 * d, dot)
            dot.color = m.away.color
            dot.alpha = a
            rect.set(split + 1.5f * d, y + 17 * d, right, y + 21 * d)
            c.drawRoundRect(rect, 2 * d, 2 * d, dot)
        }
        name.textAlign = Paint.Align.LEFT
    }
}
