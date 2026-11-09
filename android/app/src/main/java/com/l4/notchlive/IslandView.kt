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
        /** A button on an opened live activity (by its index in the activity's actions). */
        fun cardPick(index: Int) {}
        /** The song's bar was dragged to [f] (0..1). */
        fun seek(f: Float) {}
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

    /** 1.9: the prayer's colour as it gets close (0 = normal), and the unusual-weather dot. */
    var idleTone = 0
    var idleAlert = false
    var weatherAlways = false
    /** Weather glyph and its one word ("مطر"), shown in place of the temperature for a moment. */
    var weatherGlyph = ""
    var weatherWord = ""
    /** When the current idle text started showing (for its scroll). */
    private var idleTextSince = 0L
    private var idleTextShown = ""
    /** Screen recording since this time (0 = not recording): a red dot and a timer. */
    var recSince = 0L
    /** A live activity from another app (a ride, a call, a timer, music), or null. */
    var act: Live? = null
    var actIcon: android.graphics.Bitmap? = null
    /** Beside the phone's pill: a ride in its own circle (left), the match in the next one (right). */
    var ringAct: Live? = null
    /** The opened live activity (a song, a call, a timer, a ride) and its app's icon. */
    var card: Live? = null
    var cardIcon: android.graphics.Bitmap? = null
    /** Where each of the card's buttons was drawn (action index, area), and the song's bar. */
    private val cardBtns = ArrayList<Pair<Int, RectF>>()
    private val barRect = RectF()
    private var seeking = false
    private var seekF = 0f
    /** Where the last touch went down, in the view (which circle was tapped). */
    var touchX = 0f
    private var actSince = 0L
    private var actShown = ""

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

    /** Half the cut-out's width: a teardrop or a notch is wider than tall (1.8). */
    var camHW = 0f

    /** Status bar height (px) and how much the whole notch shrinks to stay inside it (1.8). */
    var sbH = 0f
    var fit = 1f

    /** "auto", "A" round the camera, "B" one side, "C" stacked under the bar, "D" one line under the bar. */
    var tplPref = "auto"

    /** Text size factor while fitting (1 → 0.7): text shrinks first, then words shorten, then items go. */
    private var fitK = 1f
    private val KS = floatArrayOf(1f, 0.9f, 0.8f, 0.7f)
    var leftMax = Float.MAX_VALUE
    var rightMax = Float.MAX_VALUE
    var scale = 1f

    /** Everything is drawn 10% above Android's base size (Omar: "a little bigger"), times the size slider. */
    // 1.8: × [fit], so on a phone with a big display size the notch still fits its status bar.
    private val d get() = resources.displayMetrics.density * (if (idleSized()) idleScale else scale) * 1.1f * fit

    /** The always-on notch has its own size (height and text), apart from the match notch. */
    var idleScale = 1f
    private fun idleSized() = idle && burst == null && !info

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
    private val rect2 = RectF()
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }

    private fun sizes() {
        val k = fitK
        score.textSize = 13.5f * d * k
        minute.textSize = 11f * d * k
        small.textSize = 8.5f * d * k
        title.textSize = 16f * d
        sub.textSize = 12f * d
        name.textSize = 12.5f * d * k
        line.textSize = 10.5f * d * k
    }

    /* ---------- geometry ---------- */

    fun compactHeight(): Int = max(2 * (camR + 5 * d), 28 * d).toInt()

    private fun gap() = if (landscape) 9 * d else max(camR, camHW) + 6 * d

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
    private var aL = 0
    private var aR = 0
    private fun leftLevel(): Int = aL
    private fun rightLevel(): Int = aR

    /**
     * 1.8 auto-fit for template A: first the text shrinks (down to 70%), then the old steps.
     * Leaves [fitK], [aL] and [aR] set. True when it still looks like a scoreboard (minute and crests).
     */
    private fun fitA(): Boolean {
        aL = 0
        aR = 0
        if (roomy) {
            fitK = 1f
            sizes()
            return true
        }
        for (k in KS) {
            fitK = k
            if (leftWidth(0) <= leftMax && rightWidth(0) <= rightMax) return true
        }
        fitK = 0.7f
        aL = (0..3).firstOrNull { leftWidth(it) <= leftMax } ?: 4
        aR = (0..1).firstOrNull { rightWidth(it) <= rightMax } ?: 2
        return aL <= 2 && aR <= 1
    }

    private fun resetFit() {
        fitK = 1f
        sizes()
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
        act?.let { a ->
            sizes()
            val ch = compactHeight().toFloat()
            // Music: just the album art left of the camera and the sound wave right of it.
            if (a.kind == "media") return gap() + ch * 0.9f + 4 * d to gap() + 24 * d
            // A live activity: the app's icon and the big value on the left, a label on the right.
            val lMax = min(leftMax + 20 * d, 170 * d)
            val rMax = min(rightMax + 20 * d, 150 * d)
            val left = gap() + minute.measureText(actBig(a)) + ch * 0.9f + 8 * d
            val right = gap() + line.measureText(a.right) + 12 * d
            return min(lMax, left) to min(rMax, max(right, ch / 2 + 12 * d))
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
            if (idleTemp.isNotEmpty()) right = max(right, gap() + minute.measureText(idleTemp) + (if (weatherGlyph.isNotEmpty()) 16 * d else 0f) + 12 * d)
            return left to right
        }
        return when (tpl()) {
            "B" -> {
                val lv = fitB()
                val w = gap() + bWidth(if (lv < 0) 3 else lv) + 10 * d
                val other = max(gap(), compactHeight() / 2f)
                resetFit()
                if (bRight()) other to w else w to other
            }
            "C", "D" -> {
                val (bw, _) = belowSize()
                bw / 2f to bw / 2f
            }
            else -> {
                fitA()
                val r = leftWidth(aL) to rightWidth(aR)
                resetFit()
                r
            }
        }
    }

    /* ---------- 1.8 templates ---------- */

    companion object {
        /** The last template drawn, for the device page. */
        var lastTpl = ""

        /** 1.9.1: with the screen off nothing asks for frames (the service redraws on screen on). */
        @Volatile
        var screenOn = true
    }

    override fun postInvalidateDelayed(delayMilliseconds: Long) {
        if (screenOn) super.postInvalidateDelayed(delayMilliseconds)
    }

    override fun postInvalidateOnAnimation() {
        if (screenOn) super.postInvalidateOnAnimation()
    }

    /** Max width of a pill under the status bar (set by Island from the width slider). */
    var belowMax = 0f

    /** The template the small notch uses now. Landscape and full screen keep the classic look. */
    fun tpl(): String {
        if (landscape || roomy) return "A"
        if (idle) {
            return when (tplPref) {
                "C", "D" -> "D"
                "A", "B" -> "A"
                else -> if (idleFitsA()) "A" else "D"
            }
        }
        if (act != null || recSince > 0) return "A"
        return when (tplPref) {
            "A", "B", "C", "D" -> tplPref
            else -> {
                // Decided on the full room (width at 100%), then drawn at the user's width.
                val (l0, r0, b0) = Triple(leftMax, rightMax, belowMax)
                if (fullLeft > 0f || fullRight > 0f) {
                    leftMax = fullLeft
                    rightMax = fullRight
                }
                if (fullBelow > 0f) belowMax = fullBelow
                val t = when {
                    fitA() -> "A"
                    fitB() >= 0 -> "B"
                    fitD() >= 0 -> "D"
                    else -> "C"
                }
                leftMax = l0
                rightMax = r0
                belowMax = b0
                resetFit()
                t
            }
        }
    }

    /** The room at 100% width (set by Island): what "auto" decides on. */
    var fullLeft = 0f
    var fullRight = 0f
    var fullBelow = 0f

    /** Templates C and D sit under the status bar, away from the camera. */
    fun below(): Boolean = !landscape && !roomy && !tucked && !circle && act == null && recSince == 0L &&
        burst == null && !info && tpl().let { it == "C" || it == "D" }

    private fun idleFitsA(): Boolean {
        if (idleText.isEmpty()) return true
        fitK = 0.8f
        sizes()
        val ok = gap() + line.measureText(idleText) + 12 * d <= min(if (fullLeft > 0f) fullLeft else leftMax, 150 * d) + 1f
        resetFit()
        return ok
    }

    /* B: everything on the roomier side of the camera. */
    private fun bRight() = rightMax >= leftMax
    private fun bWidth(lv: Int): Float {
        sizes()
        val m = match ?: return 0f
        var w = minute.measureText(clockLabel(m)) + 6 * d
        if (lv < 1 && added.isNotEmpty()) w += small.measureText(added) + 2 * d
        val crest = if (lv >= 2) crestAt(1) else crestAt(0)
        if (lv < 3) w += 2 * (crest + 5 * d) + redsWidth(true) + redsWidth(false)
        w += score.measureText(scoreText(m, true)) + score.measureText(scoreText(m, false)) + minute.measureText("–") + 8 * d
        w += 12 * d
        if (others > 0) w += 7 * d
        return w
    }

    /** Level (0 all … 3 no crests) that fits B, or -1. Leaves [fitK] set. */
    private fun fitB(): Int {
        val room = max(leftMax, rightMax) - gap() - 10 * d
        for (k in KS) {
            fitK = k
            if (bWidth(0) <= room) return 0
        }
        fitK = 0.7f
        return (1..3).firstOrNull { bWidth(it) <= room } ?: -1
    }

    private fun drawB(c: Canvas, m: Match, ch: Float, a: Int) {
        val lv = fitB().let { if (it < 0) 3 else it }
        val al = a / 255f
        val cy = ch / 2
        var x = if (bRight()) camX + gap() else camX - gap() - bWidth(lv)
        if (lv < 1 && added.isNotEmpty()) {
            small.alpha = a
            c.drawText(added, x, cy - 1 * d, small)
            x += small.measureText(added) + 2 * d
        }
        x = drawMinute(c, m, x, cy, a) + 6 * d
        val crest = if (lv >= 2) crestAt(1) else crestAt(0)
        if (lv < 3) {
            drawCrest(c, m.home.logo, x, cy - crest / 2, crest, al)
            if (reds(true) > 0) drawRedCards(c, reds(true), x - 2 * d, cy + crest / 2 - 5 * d, al)
            x += crest + 5 * d
        }
        x = drawScores(c, m, x, cy, a)
        if (lv < 3) {
            x += 5 * d
            drawCrest(c, m.away.logo, x, cy - crest / 2, crest, al)
            x += crest
            if (reds(false) > 0) drawRedCards(c, reds(false), x - min(reds(false), 3) * 4 * d + 2 * d, cy + crest / 2 - 5 * d, al)
        }
        drawLiveDot(c, m, x + 9 * d, cy, a)
    }

    /** "92'" (or VAR blinking) at [x]; returns where it ends. */
    private fun drawMinute(c: Canvas, m: Match, x: Float, cy: Float, a: Int): Float {
        val label = clockLabel(m)
        minute.color = minuteColor(m)
        minute.alpha = a
        minute.textAlign = Paint.Align.LEFT
        if (m.phase == "var" && !calm) {
            minute.alpha = (a * (0.35f + 0.65f * abs(sin(System.currentTimeMillis() / 300.0)).toFloat())).toInt()
            postInvalidateDelayed(50)
        }
        c.drawText(label, x, baseline(minute, cy), minute)
        return x + minute.measureText(label)
    }

    /** "2 – 1" from [x]; returns where it ends. */
    private fun drawScores(c: Canvas, m: Match, x0: Float, cy: Float, a: Int): Float {
        var x = x0
        val hs = scoreText(m, true)
        drawScore(c, hs, x, cy, m.home.color, homeFlash, a)
        x += score.measureText(hs) + 4 * d
        minute.color = 0xFF9CA3AF.toInt()
        minute.alpha = a
        minute.textAlign = Paint.Align.LEFT
        c.drawText("–", x, baseline(minute, cy), minute)
        x += minute.measureText("–") + 4 * d
        val aw = scoreText(m, false)
        drawScore(c, aw, x, cy, m.away.color, awayFlash, a)
        return x + score.measureText(aw)
    }

    private fun drawLiveDot(c: Canvas, m: Match, dx: Float, cy: Float, a: Int) {
        val (color, breathe) = when {
            m.state == Api.POST -> 0xFF6B7280.toInt() to false
            m.state == Api.PRE || m.phase == "break" -> 0xFFFBBF24.toInt() to false
            m.phase == "var" -> 0xFF60A5FA.toInt() to true
            else -> 0xFF34D399.toInt() to true
        }
        val level = if (breathe && !calm) 0.35f + 0.65f * ((sin(System.currentTimeMillis() / 450.0) + 1) / 2).toFloat() else 1f
        dot.color = color
        dot.alpha = (a * level).toInt()
        c.drawCircle(dx, cy, 3 * d, dot)
        if (breathe && !calm) {
            dot.alpha = (a * level * 0.25f).toInt()
            c.drawCircle(dx, cy, 5.5f * d, dot)
            // 1.9.1: the breathing is slow (~3 s), 10 frames a second look the same.
            postInvalidateDelayed(100)
        }
        if (others > 0) {
            dot.color = Color.WHITE
            dot.alpha = (a * 0.7f).toInt()
            c.drawCircle(dx + 7 * d, cy, 2 * d, dot)
        }
    }

    /* D: one line under the status bar. Levels: 0 names · 1 no names · 2 no "+N" · 3 no crests. */
    private fun dH() = 22 * d
    private fun dCrest() = dH() - 7 * d
    private fun dPad() = dH() * 0.45f
    private fun dWidth(lv: Int): Float {
        sizes()
        val m = match ?: return 0f
        var w = 2 * dPad() + minute.measureText(clockLabel(m)) + 6 * d
        if (lv < 2 && added.isNotEmpty()) w += small.measureText(added) + 2 * d
        if (lv < 3) w += 2 * (dCrest() + 5 * d) + redsWidth(true) + redsWidth(false)
        if (lv < 1) w += name.measureText(teamName(true)) + name.measureText(teamName(false)) + 10 * d
        w += score.measureText(scoreText(m, true)) + score.measureText(scoreText(m, false)) + minute.measureText("–") + 8 * d
        w += 12 * d
        if (others > 0) w += 7 * d
        return w
    }

    private fun fitD(): Int {
        val room = if (belowMax > 0f) belowMax else resources.displayMetrics.widthPixels * 0.62f
        for (lv in 0..3) for (k in KS) {
            fitK = k
            if (dWidth(lv) <= room) return lv
        }
        return -1
    }

    /* C: two rows (crest, name, score) with the minute and the live dot in a column on the left. */
    private fun cRow() = 18 * d
    private fun cH() = 2 * cRow() + 6 * d
    private fun cCol(m: Match): Float = max(minute.measureText(clockLabel(m)), small.measureText(added) + 12 * d)
    private fun cWidth(names: Boolean): Float {
        sizes()
        val m = match ?: return 0f
        val row = { home: Boolean ->
            (cRow() - 3 * d) + 5 * d + (if (names) name.measureText(teamName(home)) + 6 * d else 0f) + score.measureText(scoreText(m, home)) + redsWidth(home)
        }
        return 2 * dPad() + cCol(m) + 10 * d + max(row(true), row(false))
    }

    private fun fitC(): Boolean {
        val room = (if (belowMax > 0f) belowMax else resources.displayMetrics.widthPixels * 0.62f) * 0.7f
        for (k in KS) {
            fitK = k
            if (cWidth(true) <= room) return true
        }
        fitK = 0.7f
        return false
    }

    /* The always-on notch as a line under the bar: date/prayer · weather. */
    private fun idleBelowWidth(): Float {
        sizes()
        val room = if (belowMax > 0f) belowMax else resources.displayMetrics.widthPixels * 0.62f
        var w = 2 * dPad()
        if (idleText.isNotEmpty()) w += line.measureText(idleText)
        if (idleTemp.isNotEmpty()) w += 10 * d + minute.measureText(idleTemp) + (if (weatherGlyph.isNotEmpty()) 16 * d else 0f)
        if (idleText.isEmpty() && idleTemp.isEmpty()) w += 10 * d
        return min(room, w)
    }

    /** Size of the pill under the bar (C or D, or the always-on line). */
    fun belowSize(): Pair<Int, Int> {
        val m = match
        val r = when {
            idle -> idleBelowWidth() to dH()
            tpl() == "C" -> {
                fitC()
                cWidth(true).let { w -> if (w <= belowMax * 0.7f || belowMax <= 0f) w else cWidth(false) } to cH()
            }
            else -> {
                val lv = fitD().let { if (it < 0) 3 else it }
                dWidth(lv) to dH()
            }
        }
        resetFit()
        return (r.first.toInt() + 1) to r.second.toInt()
    }

    private fun drawBelow(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        val rad = if (h > dH() + 1) min(h / 2, 12 * d) else h / 2
        c.drawRoundRect(rect, rad, rad, bg)
        val m = match
        if (idle) {
            lastTpl = "D (idle)"
            drawIdleBelow(c, w, h)
            return
        }
        m ?: return
        val cy = h / 2
        if (tpl() == "C") {
            lastTpl = "C"
            fitC()
            val names = cWidth(true) <= belowMax * 0.7f || belowMax <= 0f
            val x0 = dPad()
            val y1 = 3 * d + cRow() / 2
            val y2 = y1 + cRow()
            drawMinute(c, m, x0, y1, 255)
            if (added.isNotEmpty()) {
                small.alpha = 255
                c.drawText(added, x0, baseline(small, y2), small)
            }
            drawLiveDot(c, m, x0 + (if (added.isNotEmpty()) small.measureText(added) + 6 * d else 3 * d), y2, 255)
            val cx = x0 + cCol(m) + 10 * d
            for ((home, y) in listOf(true to y1, false to y2)) {
                var x = cx
                val crest = cRow() - 3 * d
                drawCrest(c, m.side(home).logo, x, y - crest / 2, crest, 1f)
                x += crest + 5 * d
                if (names) {
                    name.alpha = 255
                    name.textAlign = Paint.Align.LEFT
                    c.drawText(teamName(home), x, baseline(name, y), name)
                    x += name.measureText(teamName(home)) + 6 * d
                }
                drawScore(c, scoreText(m, home), x, y, m.side(home).color, if (home) homeFlash else awayFlash, 255)
                x += score.measureText(scoreText(m, home))
                if (reds(home) > 0) drawRedCards(c, reds(home), x + 3 * d, y - 2.5f * d, 1f)
            }
            resetFit()
            return
        }
        lastTpl = "D"
        val lv = fitD().let { if (it < 0) 3 else it }
        var x = dPad()
        if (lv < 2 && added.isNotEmpty()) {
            small.alpha = 255
            c.drawText(added, x, cy - 1 * d, small)
            x += small.measureText(added) + 2 * d
        }
        x = drawMinute(c, m, x, cy, 255) + 6 * d
        val crest = dCrest()
        if (lv < 3) {
            drawCrest(c, m.home.logo, x, cy - crest / 2, crest, 1f)
            if (reds(true) > 0) drawRedCards(c, reds(true), x - 2 * d, cy + crest / 2 - 5 * d, 1f)
            x += crest + 5 * d
        }
        name.alpha = 255
        name.textAlign = Paint.Align.LEFT
        if (lv < 1) {
            c.drawText(teamName(true), x, baseline(name, cy), name)
            x += name.measureText(teamName(true)) + 5 * d
        }
        x = drawScores(c, m, x, cy, 255)
        if (lv < 1) {
            x += 5 * d
            c.drawText(teamName(false), x, baseline(name, cy), name)
            x += name.measureText(teamName(false))
        }
        if (lv < 3) {
            x += 5 * d
            drawCrest(c, m.away.logo, x, cy - crest / 2, crest, 1f)
            x += crest
            if (reds(false) > 0) drawRedCards(c, reds(false), x - min(reds(false), 3) * 4 * d + 2 * d, cy + crest / 2 - 5 * d, 1f)
        }
        drawLiveDot(c, m, x + 9 * d, cy, 255)
        resetFit()
    }

    /** Small orange dot: unusual weather today (1.9). */
    private fun alertDot(c: Canvas, x: Float, y: Float) {
        dot.color = 0xFFF59E0B.toInt()
        dot.alpha = 255
        c.drawCircle(x, y, 2.2f * d, dot)
    }

    private fun drawIdleBelow(c: Canvas, w: Float, h: Float) {
        val cy = h / 2
        val now = System.currentTimeMillis()
        val busyBg = weather.isNotEmpty() && (weatherAlways || now < weatherUntil)
        if (busyBg) {
            c.save()
            path.reset()
            path.addRoundRect(rect, h / 2, h / 2, Path.Direction.CW)
            c.clipPath(path)
            val f = fx?.takeIf { it.kind == weather } ?: WeatherFx(weather).also { fx = it }
            f.draw(c, 0f, w, h, now)
            c.restore()
            postInvalidateOnAnimation()
        }
        var right = w - dPad()
        if (idleTemp.isNotEmpty()) {
            val showWord = weatherWord.isNotEmpty() && now < weatherUntil - 2_000L
            val label = if (showWord) weatherWord else idleTemp
            minute.color = Color.WHITE
            minute.alpha = 255
            minute.textAlign = Paint.Align.RIGHT
            shadowed(minute, busyBg)
            c.drawText(label, right, baseline(minute, cy), minute)
            if (idleAlert) alertDot(c, right + 3.5f * d, cy - h * 0.24f)
            right -= minute.measureText(label)
            if (weatherGlyph.isNotEmpty()) {
                val sz = 14 * d
                WeatherFx.icon(c, weatherGlyph, right - 4 * d - sz / 2, cy, sz)
                right -= sz + 4 * d
            }
            shadowed(minute, false)
            minute.textAlign = Paint.Align.LEFT
            right -= 10 * d
            if (showWord) postInvalidateDelayed(250)
        }
        if (idleText.isNotEmpty()) {
            if (idleText != idleTextShown) {
                idleTextShown = idleText
                idleTextSince = now
            }
            line.color = if (idleTone != 0) idleTone else 0xFFE5E7EB.toInt()
            line.alpha = 255
            shadowed(line, busyBg)
            marquee(c, idleText, line, dPad(), right, baseline(line, cy), idleTextSince)
            shadowed(line, false)
            line.color = 0xFFC8C8C8.toInt()
        } else if (idleTemp.isEmpty()) {
            dot.color = 0xFF6B7280.toInt()
            dot.alpha = 255
            c.drawCircle(w / 2, cy, 2.5f * d, dot)
        }
    }

    /** The tucked mark: a window right of the camera, wide enough to swipe on. */
    fun tuckWidth(): Int = (40 * d).toInt()

    /**
     * An event inside the notch grows only on the side its text needs: (left, right) from the
     * camera, or null when the view isn't playing an event inline.
     */
    fun inlineExtent(): Pair<Float, Float>? {
        val b = burst ?: return null
        if (!isInline(b)) return null
        sizes()
        val (lw, rw) = compactExtent()
        if (b.kind == "news") {
            // About the size of the always-on notch at its widest, a little more on the right.
            val side = max(lw, min(leftMax, 150 * d))
            return side to max(rw, min(rightMax, 150 * d) + 10 * d)
        }
        val need = inlineWordRight() + inlineWordWidth(b) + 12 * d
        return max(lw, min(need, leftMax + 30 * d)) to rw
    }

    fun bigWidth(screenW: Int): Int {
        val full = min(screenW - (24 * resources.displayMetrics.density).toInt(), (360 * d).toInt())
        val b = burst ?: return full
        if (!isInline(b)) return full
        if (b.kind == "news") return min(full, (330 * d).toInt())
        // Match events: just wide enough for the word on the left, symmetric round the camera.
        sizes()
        val (lw, rw) = compactExtent()
        val e = inlineExtent() ?: return full
        return (e.first + e.second).toInt() + 1
    }

    private val INLINE = setOf("goal", "red", "var", "pen", "whistle", "lineups", "notice", "news")

    /** Played inside the notch (not opened yet). */
    // 1.8: only template A plays events inside the notch; B/C/D open the event view instead.
    fun isInline(b: Burst?) = b != null && inlineEvents && !b.expanded && b.kind in INLINE && tpl() == "A"

    /** Distance from the camera to the right edge of the word (left of the home crest). */
    private fun inlineWordRight(): Float {
        val m = match ?: return gap()
        return gap() + score.measureText(scoreText(m, true)) + 5 * d + baseCrest() + 7 * d
    }

    private fun inlineWords(b: Burst): List<String> = listOf(b.title)

    /** The second line under the camera: the headline, or who scored and when ("صلاح · 88'"). */
    private fun rowText(b: Burst): String = if (b.kind == "news") b.sub else b.sub.replace("•", "·").trim()

    private fun hasRow(b: Burst) = rowText(b).isNotBlank()
    private fun rowHeight() = 22 * d

    /** Height of an event playing inside the notch: one row more when there's a second line. */
    fun inlineHeight(): Int {
        val b = burst ?: return compactHeight()
        return compactHeight() + if (isInline(b) && hasRow(b)) rowHeight().toInt() else 0
    }

    private fun inlineWordWidth(b: Burst): Float {
        val old = title.textSize
        title.textSize = 12.5f * d
        val w = inlineWords(b).maxOf { title.measureText(it) }
        title.textSize = old
        return min(w, 130 * d)
    }

    /**
     * Text that may not fit [areaL]..[areaR]: shown from its start, then (after a beat) it slides
     * once at an easy reading speed to show the rest, and stays. Arabic slides left-to-right.
     */
    private fun marquee(c: Canvas, text: String, p: Paint, areaL: Float, areaR: Float, y: Float, since: Long, repeat: Int = 1) {
        val aw = areaR - areaL
        if (aw <= 4 * d) return
        val oldAlign = p.textAlign
        val oldSize = p.textSize
        val rtl = text.any { it in '\u0600'..'\u06FF' }
        // First the text shrinks a little (to 80%) so it fits whole without moving.
        var tw = p.measureText(text)
        if (tw > aw && tw * 0.8f <= aw) {
            p.textSize = oldSize * aw / tw
            tw = p.measureText(text)
        } else if (tw > aw) {
            p.textSize = oldSize * 0.8f
            tw = p.measureText(text)
        }
        c.save()
        c.clipRect(areaL, 0f, areaR, height.toFloat())
        if (tw <= aw + 0.5f) {
            p.textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
            c.drawText(text, if (rtl) areaR else areaL, y, p)
        } else {
            // Still too long: it slides to show the end, waits, slides back, and rests on its start.
            val over = tw - aw
            val speed = 38 * d / 1000f
            val run = (over / speed).toLong()
            var age = (System.currentTimeMillis() - since - 900).coerceAtLeast(0)
            // News goes through twice (1.9), with a rest on its start between.
            val cycle = 2 * run + 2700
            if (repeat > 1 && age < cycle * repeat) age %= cycle
            val shift = when {
                age < run -> age * speed
                age < run + 1200 -> over
                age < 2 * run + 1200 -> over - (age - run - 1200) * speed
                else -> 0f
            }.coerceIn(0f, over)
            p.textAlign = Paint.Align.LEFT
            val x = if (rtl) areaL - over + shift else areaL - shift
            c.drawText(text, x, y, p)
            if (age < 2 * run + 1200) postInvalidateOnAnimation()
        }
        c.restore()
        p.textAlign = oldAlign
        p.textSize = oldSize
    }

    /** Readable over the weather: a soft dark shadow. */
    private fun shadowed(p: Paint, on: Boolean) {
        if (on) p.setShadowLayer(3 * d, 0f, 0f, 0xE6000000.toInt()) else p.clearShadowLayer()
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
        isInline(burst) -> inlineHeight()
        info && burst == null -> (top() + 40 * d + infoLines() * 15 * d + statsHeight() + 6 * d + HINT_ROW * d).toInt()
        burst?.kind == "shortcuts" -> (top() + 6 * d + 2 * SLOT_ROW * d + (if (burst?.sub.isNullOrBlank()) 0f else 40 * d) + 8 * d).toInt()
        burst?.kind == "news" || burst?.kind == "phone" ->
            (top() + 22 * d + max(1, newsLines(burst!!).size) * 16 * d + 8 * d).toInt()
        burst?.kind == "menu" -> (top() + (burst?.items?.size ?: 3) * MENU_ROW * d + 8 * d).toInt()
        burst?.kind == "card" -> (top() + cardBody() * d).toInt()
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
                seeking = burst?.kind == "card" && t > 0.9f && !barRect.isEmpty &&
                    e.y in barRect.top - 12 * dpx..barRect.bottom + 12 * dpx && e.x in barRect.left - 6 * dpx..barRect.right + 6 * dpx
                if (seeking) {
                    seekF = ((e.x - barRect.left) / barRect.width()).coerceIn(0f, 1f)
                    invalidate()
                    return true
                }
                touchX = e.x
                downLX = e.x
                downLY = e.y
                downX = e.rawX
                downY = e.rawY
                pressed = true
                longDone = false
                postDelayed(longPress, 500)
            }
            MotionEvent.ACTION_MOVE -> {
                if (seeking) {
                    seekF = ((e.x - barRect.left) / barRect.width()).coerceIn(0f, 1f)
                    invalidate()
                    return true
                }
                if (abs(e.rawX - downX) > 12 * dpx || abs(e.rawY - downY) > 12 * dpx) {
                    pressed = false
                    removeCallbacks(longPress)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (seeking) {
                    seeking = false
                    gestures.seek(seekF)
                    return true
                }
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
                        if (b?.kind == "card" && t > 0.9f) {
                            val hit = cardBtns.firstOrNull { (_, r) -> e.x in r.left..r.right && e.y in r.top..r.bottom }
                            if (hit != null) gestures.cardPick(hit.first) else gestures.tap()
                        } else if (b?.kind == "shortcuts" && t > 0.9f) {
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
        fitK = 1f
        sizes()
        if (recSince > 0 && burst == null && !info) {
            drawRec(c)
            return
        }
        if (act != null && burst == null && !info && t == 0f && !tucked) {
            drawAct(c)
            return
        }
        if (t == 0f && below()) {
            drawBelow(c)
            return
        }
        if (idle && burst == null && !info && t == 0f && !tucked) {
            lastTpl = "A (idle)"
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
        if (t == 0f) ringAct?.let { ra -> if (sideW() > 0f) drawRing(c, ra, compactHeight() / 2f) }
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
        if (compactAlpha > 0f) {
            drawCompact(c, m, ch, compactAlpha)
            resetFit()
        }
        val bigAlpha = ((t - 0.5f) * 2f).coerceIn(0f, 1f)
        if (bigAlpha > 0f) {
            if (b?.kind == "menu") drawMenu(c, b, camX - bw / 2, camX + bw / 2, bigAlpha)
            else if (b?.kind == "stats") drawStats(c, m, b, camX - bw / 2, camX + bw / 2, bigAlpha)
            else if (b?.kind == "shortcuts") drawShortcuts(c, b, camX - bw / 2, camX + bw / 2, bigAlpha)
            else if (b?.kind == "card") drawCard(c, camX - bw / 2, camX + bw / 2, bigAlpha)
            else if (b != null) drawBurst(c, m, b, camX - bw / 2, camX + bw / 2, h, bigAlpha)
            else if (info) {
                drawInfo(c, m, camX - bw / 2, camX + bw / 2, bigAlpha)
                drawHint(c, camX - bw / 2, camX + bw / 2, bigHeight().toFloat(), bigAlpha)
            }
        }
        c.restore()
    }

    /** The small circle beside Xiaomi's island: the score, ringed by the live colour. */
    /** Room for one circle, or two (a ride and the match) side by side. */
    fun circleWidth(): Int {
        val ch = compactHeight()
        return if (ringAct != null && (match?.id ?: -1) >= 0) ch * 2 + (4 * d).toInt() else ch
    }

    /** Room left of the match notch for an activity's circle (the scoreboard keeps the middle). */
    fun sideW(): Float = if (ringAct != null && !circle && (match?.id ?: -1) >= 0 && burst == null && !info && !tucked && !below()) compactHeight() + 5 * d else 0f

    /** An activity in a small circle: the song's art with its progress, a timer, a call, a ride. */
    private fun drawRing(c: Canvas, a: Live, r: Float) {
        val now = System.currentTimeMillis()
        when (a.kind) {
            "media" -> {
                c.drawCircle(r, r, r, bg)
                val art = a.pic
                val ir = r - 3f * d
                if (art != null) {
                    c.save()
                    path.reset()
                    path.addCircle(r, r, ir, Path.Direction.CW)
                    c.clipPath(path)
                    rect2.set(r - ir, r - ir, r + ir, r + ir)
                    bmp.alpha = 255
                    c.drawBitmap(art, null, rect2, bmp)
                    c.restore()
                }
                val col = accentOf(art)
                stroke.strokeWidth = 1.8f * d
                stroke.color = 0xFF3A3F47.toInt()
                stroke.alpha = 255
                c.drawCircle(r, r, r - 1f * d, stroke)
                val pos = if (a.playing && a.posAt > 0) a.posMs + ((android.os.SystemClock.elapsedRealtime() - a.posAt) * a.speed).toLong() else a.posMs
                if (a.durMs > 0) {
                    stroke.color = col
                    rect2.set(1f * d, 1f * d, 2 * r - 1f * d, 2 * r - 1f * d)
                    stroke.strokeCap = Paint.Cap.ROUND
                    c.drawArc(rect2, -90f, 360f * (pos.toFloat() / a.durMs).coerceIn(0f, 1f), false, stroke)
                    stroke.strokeCap = Paint.Cap.BUTT
                }
                if (a.playing) postInvalidateDelayed(1_000L)
            }
            "timer", "call" -> {
                c.drawCircle(r, r, r, bg)
                val col = if (a.kind == "call") 0xFF34D399.toInt() else 0xFFFBBF24.toInt()
                stroke.color = col
                stroke.alpha = 255
                stroke.strokeWidth = 1.6f * d
                c.drawCircle(r, r, r - 1.6f * d, stroke)
                val secs = if (a.chronoBase > 0) abs(now - a.chronoBase) / 1000 else -1
                val txt = when {
                    secs < 0 -> "•"
                    a.kind == "timer" && secs >= 60 -> ((secs + 59) / 60).toString()
                    a.kind == "timer" -> secs.toString()
                    else -> "%d:%02d".format(secs / 60, secs % 60)
                }
                val old = score.textSize
                score.textSize = (if (txt.length > 3) 7f else if (txt.length > 2) 8.5f else 10.5f) * d
                score.color = col
                score.alpha = 255
                score.textAlign = Paint.Align.CENTER
                c.drawText(txt, r, baseline(score, r), score)
                score.textAlign = Paint.Align.LEFT
                score.textSize = old
                score.color = Color.WHITE
                postInvalidateDelayed(1_000L)
            }
            else -> drawRideCircle(c, a, r)
        }
    }

    /** The ride's circle: minutes left (counting down), or a pulsing dot when there's no time. */
    private fun drawRideCircle(c: Canvas, a: Live, r: Float) {
        c.drawCircle(r, r, r, bg)
        val now = System.currentTimeMillis()
        val green = 0xFF34D399.toInt()
        stroke.color = green
        stroke.alpha = 255
        stroke.strokeWidth = 1.6f * d
        c.drawCircle(r, r, r - 1.6f * d, stroke)
        if (a.target > 0L) {
            val mins = ((a.target - now + 59_999L) / 60_000L).coerceAtLeast(0L)
            val txt = if (mins > 99) "99+" else mins.toString()
            val old = score.textSize
            score.textSize = (if (txt.length > 2) 8.5f else 11f) * d
            score.color = green
            score.alpha = 255
            score.textAlign = Paint.Align.CENTER
            c.drawText(txt, r, baseline(score, r), score)
            score.textAlign = Paint.Align.LEFT
            score.textSize = old
            score.color = Color.WHITE
            postInvalidateDelayed(5_000L)
        } else {
            // Waiting for a driver, or the driver is here: a breathing dot.
            val level = 0.45f + 0.55f * ((sin(now / 450.0) + 1) / 2).toFloat()
            dot.color = green
            dot.alpha = (255 * level).toInt()
            c.drawCircle(r, r, 3.2f * d, dot)
            if (!calm) postInvalidateDelayed(100)
        }
    }

    private fun drawCircle(c: Canvas) {
        val m = match ?: return
        val size = compactHeight().toFloat()
        val r = size / 2
        ringAct?.let { a ->
            drawRing(c, a, r)
            if (m.id < 0) return
            c.translate(size + 4 * d, 0f)
        }
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
        if (breathe && !calm) postInvalidateDelayed(100)
    }

    /** Always-on, nothing on: a black ring round the camera and a small grey dot. */
    private fun drawIdle(c: Canvas) {
        val ch = compactHeight().toFloat()
        val (lw, rw) = compactExtent()
        rect.set(camX - lw, 0f, camX + rw, ch)
        c.drawRoundRect(rect, ch / 2, ch / 2, bg)
        val now = System.currentTimeMillis()
        // The service sends no weather when it must stay still (saver chosen, battery ≤ 10%).
        if (weather.isNotEmpty() && (weatherAlways || now < weatherUntil)) {
            c.save()
            path.reset()
            path.addRoundRect(rect, ch / 2, ch / 2, Path.Direction.CW)
            c.clipPath(path)
            val f = fx?.takeIf { it.kind == weather } ?: WeatherFx(weather).also { fx = it }
            // Fades in, and out over its last second instead of stopping mid-move.
            if (fxSince == 0L) fxSince = now
            val fade = if (weatherAlways) min(1f, (now - fxSince) / 400f) else minOf(1f, (now - fxSince) / 400f, (weatherUntil - now) / 1000f)
            c.saveLayerAlpha(rect, (255 * fade.coerceIn(0f, 1f)).toInt())
            f.draw(c, rect.left, rect.right, ch, now)
            c.restore()
            c.restore()
            postInvalidateOnAnimation()
        } else {
            fxSince = 0L
        }
        val cy = ch / 2
        val busyBg = weather.isNotEmpty() && (weatherAlways || now < weatherUntil)
        // Left: the date / prayer / match, starting at the notch's left edge (the space stays by the camera).
        if (idleText.isNotEmpty()) {
            if (idleText != idleTextShown) {
                idleTextShown = idleText
                idleTextSince = now
            }
            line.color = if (idleTone != 0) idleTone else 0xFFE5E7EB.toInt()
            line.alpha = 255
            shadowed(line, busyBg)
            marquee(c, idleText, line, rect.left + ch / 2, camX - gap() - 2 * d, baseline(line, cy), idleTextSince)
            shadowed(line, false)
            line.color = 0xFFC8C8C8.toInt()
        }
        // Right: the weather glyph and temperature, ending at the notch's right edge.
        // For the first seconds of a weather moment, its word ("مطر") stands in for the number.
        if (idleTemp.isNotEmpty()) {
            val showWord = weatherWord.isNotEmpty() && now < weatherUntil - 2_000L
            val label = if (showWord) weatherWord else idleTemp
            minute.color = Color.WHITE
            minute.alpha = 255
            minute.textAlign = Paint.Align.RIGHT
            shadowed(minute, busyBg)
            val right = rect.right - ch / 2
            c.drawText(label, right, baseline(minute, cy), minute)
            if (weatherGlyph.isNotEmpty()) {
                val sz = 15 * d
                WeatherFx.icon(c, weatherGlyph, right - minute.measureText(label) - 4 * d - sz / 2, cy, sz)
            }
            shadowed(minute, false)
            minute.textAlign = Paint.Align.LEFT
            if (showWord) postInvalidateDelayed(250)
            if (idleAlert) alertDot(c, right + 3.5f * d, cy - ch * 0.24f)
        } else {
            dot.color = 0xFF6B7280.toInt()
            dot.alpha = 255
            c.drawCircle(rect.right - 8 * d, cy, 2.5f * d, dot)
        }
        // (1.9.1: no endless redraw for long text: marquee() asks for frames only while it moves.)
    }

    /** Sound bars that move while the song plays (still when paused), in the album's colour. */
    private fun drawWave(c: Canvas, x0: Float, cy: Float, h: Float, color: Int, playing: Boolean) {
        val now = System.currentTimeMillis()
        dot.color = color
        dot.alpha = 255
        val bw = 2.4f * d
        val phases = floatArrayOf(0f, 1.3f, 0.4f, 2.1f, 0.9f)
        for (i in 0 until 5) {
            val k = if (playing && !calm) 0.25f + 0.75f * ((sin(now / 140.0 + phases[i] * 2) + 1) / 2).toFloat() else 0.22f
            val bh = max(bw, h * k)
            val x = x0 + i * (bw + 2 * d)
            rect2.set(x, cy - bh / 2, x + bw, cy + bh / 2)
            c.drawRoundRect(rect2, bw / 2, bw / 2, dot)
        }
        if (playing && !calm) postInvalidateDelayed(33)
    }

    private var accentBm: android.graphics.Bitmap? = null
    private var accentCol = 0xFFF08A4B.toInt()

    /** The album art's liveliest colour (like Xiaomi's island tints its wave). */
    private fun accentOf(bm: android.graphics.Bitmap?): Int {
        if (bm == null) return 0xFF34D399.toInt()
        if (bm === accentBm) return accentCol
        accentBm = bm
        var best = 0xFFF08A4B.toInt()
        var bestScore = 0f
        try {
            val sm = android.graphics.Bitmap.createScaledBitmap(bm, 24, 24, true)
            val hsv = FloatArray(3)
            for (y in 0 until 24) for (x in 0 until 24) {
                val px = sm.getPixel(x, y)
                Color.colorToHSV(px, hsv)
                if (hsv[2] < 0.35f) continue
                val score = hsv[1] * hsv[2]
                if (score > bestScore) {
                    bestScore = score
                    best = px
                }
            }
            if (bestScore < 0.15f) best = 0xFFE5E7EB.toInt()
        } catch (e: Exception) {
            // Keep the default.
        }
        accentCol = best or 0xFF000000.toInt()
        return accentCol
    }

    /** The opened card's height under the camera row (in dp). */
    private fun cardBody(): Float {
        val a = card ?: return 60f
        val btns = if (a.actions.isNotEmpty()) 58f else 0f
        return when (a.kind) {
            "media" -> 54f + 12f + 44f + 6f + 20f + 14f
            "timer" -> 22f + 50f + btns + 12f
            "call" -> 60f + btns + 14f
            else -> 54f + btns + 14f
        }
    }

    private fun fmtMs(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private val cardTitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val cardSub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFA1A7B1.toInt() }
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = android.graphics.PorterDuffColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
    }

    /** A picture in a rounded square (or a circle), or the first letter on a colour when there's none. */
    private fun drawPic(c: Canvas, bm: android.graphics.Bitmap?, x: Float, y: Float, s: Float, round: Boolean, letter: String) {
        rect2.set(x, y, x + s, y + s)
        val rr = if (round) s / 2 else s * 0.26f
        if (bm != null) {
            c.save()
            path.reset()
            path.addRoundRect(rect2, rr, rr, Path.Direction.CW)
            c.clipPath(path)
            bmp.alpha = 255
            c.drawBitmap(bm, null, rect2, bmp)
            c.restore()
            artEdge(c, rect2, rr)
        } else {
            dot.color = 0xFF6366F1.toInt()
            dot.alpha = 255
            c.drawRoundRect(rect2, rr, rr, dot)
            cardTitle.textSize = s * 0.4f
            cardTitle.textAlign = Paint.Align.CENTER
            c.drawText(letter.take(1).uppercase(), x + s / 2, baseline(cardTitle, y + s / 2), cardTitle)
            cardTitle.textAlign = Paint.Align.LEFT
        }
    }

    /** A thin light edge, so dark album art doesn't vanish into the black notch. */
    private fun artEdge(c: Canvas, r: RectF, rr: Float) {
        stroke.color = 0x40FFFFFF
        stroke.alpha = 0x40
        stroke.strokeWidth = 1f * d
        c.drawRoundRect(r, rr, rr, stroke)
    }

    /** Title and a second line beside a picture. */
    private fun drawHead(c: Canvas, x: Float, r: Float, y: Float, h: Float, t1: String, t2: String, t2Color: Int, big: Boolean = false) {
        val w = r - x
        cardTitle.textSize = (if (big) 21f else 17f) * d
        cardSub.textSize = 13f * d
        cardSub.color = t2Color
        val gapY = 4 * d
        val total = cardTitle.textSize + (if (t2.isNotEmpty()) gapY + cardSub.textSize else 0f)
        var ty = y + (h - total) / 2
        c.drawText(TextUtils.ellipsize(t1, cardTitle, w, TextUtils.TruncateAt.END).toString(), x, ty - cardTitle.ascent() * 0.92f, cardTitle)
        ty += cardTitle.textSize + gapY
        if (t2.isNotEmpty()) c.drawText(TextUtils.ellipsize(t2, cardSub, w, TextUtils.TruncateAt.END).toString(), x, ty - cardSub.ascent() * 0.92f, cardSub)
    }

    /** The app's buttons as a row of pills: red to end or decline, green to answer, white when mute is on. */
    private fun drawPills(c: Canvas, a: Live, l: Float, r: Float, y: Float) {
        val n = a.actions.size
        if (n == 0) return
        val gapX = 10 * d
        val h = 42 * d
        val w = (r - l - gapX * (n - 1)) / n
        val micMuted = try {
            (context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).isMicrophoneMute
        } catch (e: Exception) {
            false
        }
        a.actions.forEachIndexed { i, act ->
            val x = l + i * (w + gapX)
            val box = RectF(x, y, x + w, y + h)
            val on = act.role == "mute" && act.intent == null && micMuted
            val (fill, ink) = when {
                act.role == "decline" || act.role == "hangup" -> 0xFFF43F5E.toInt() to Color.WHITE
                act.role == "answer" -> 0xFF34D399.toInt() to 0xFF052E1C.toInt()
                on -> Color.WHITE to Color.BLACK
                else -> 0x22FFFFFF to Color.WHITE
            }
            dot.color = fill
            dot.alpha = Color.alpha(fill)
            c.drawRoundRect(box, h / 2, h / 2, dot)
            cardTitle.textSize = 14f * d
            cardTitle.color = ink
            val label = act.title.ifBlank {
                when (act.role) {
                    "hangup" -> L.t("call_hang")
                    "decline" -> L.t("call_decline")
                    "answer" -> L.t("call_answer")
                    "mute" -> L.t("call_mute")
                    else -> ""
                }
            }
            val iconW = if (act.icon != null) 18 * d + 6 * d else 0f
            val text = TextUtils.ellipsize(label, cardTitle, w - 20 * d - iconW, TextUtils.TruncateAt.END).toString()
            val tw = cardTitle.measureText(text) + iconW
            var tx = box.centerX() - tw / 2
            act.icon?.let { ic ->
                tint.colorFilter = android.graphics.PorterDuffColorFilter(ink, android.graphics.PorterDuff.Mode.SRC_IN)
                rect2.set(tx, box.centerY() - 9 * d, tx + 18 * d, box.centerY() + 9 * d)
                c.drawBitmap(ic, null, rect2, tint)
                tx += iconW
            }
            c.drawText(text, tx, baseline(cardTitle, box.centerY()), cardTitle)
            cardTitle.color = Color.WHITE
            cardBtns += i to box
        }
    }

    /** Media glyphs, drawn so they stay sharp at any size. */
    private fun glyph(c: Canvas, kind: String, cx: Float, cy: Float, s: Float) {
        dot.color = Color.WHITE
        dot.alpha = 255
        path.reset()
        val h = s / 2
        when (kind) {
            "play" -> {
                path.moveTo(cx - h * 0.6f, cy - h); path.lineTo(cx + h * 0.85f, cy); path.lineTo(cx - h * 0.6f, cy + h); path.close()
                c.drawPath(path, dot)
            }
            "pause" -> {
                rect2.set(cx - h * 0.7f, cy - h, cx - h * 0.2f, cy + h); c.drawRoundRect(rect2, 2 * d, 2 * d, dot)
                rect2.set(cx + h * 0.2f, cy - h, cx + h * 0.7f, cy + h); c.drawRoundRect(rect2, 2 * d, 2 * d, dot)
            }
            "prev", "next" -> {
                val dir = if (kind == "next") 1 else -1
                path.moveTo(cx - dir * h * 0.75f, cy - h * 0.8f); path.lineTo(cx + dir * h * 0.45f, cy); path.lineTo(cx - dir * h * 0.75f, cy + h * 0.8f); path.close()
                c.drawPath(path, dot)
                val bx = cx + dir * h * 0.55f
                rect2.set(min(bx, bx + dir * h * 0.22f), cy - h * 0.8f, max(bx, bx + dir * h * 0.22f), cy + h * 0.8f)
                c.drawRoundRect(rect2, 1.5f * d, 1.5f * d, dot)
            }
        }
    }

    /** An opened live activity: details, and the app's own buttons (a tap anywhere else opens the app). */
    private fun drawCard(c: Canvas, l0: Float, r0: Float, alpha: Float) {
        val a = card ?: return
        cardBtns.clear()
        barRect.setEmpty()
        c.saveLayerAlpha(l0, 0f, r0, bigHeight().toFloat(), (255 * alpha).toInt())
        val pad = 18 * d
        val l = l0 + pad
        val r = r0 - pad
        val y0 = top()
        when (a.kind) {
            "media" -> {
                val s = 54 * d
                drawPic(c, a.pic ?: cardIcon, l, y0, s, false, a.title)
                drawHead(c, l + s + 12 * d, r, y0, s, a.title, a.text, 0xFFA1A7B1.toInt())
                // Controls: the app's two extras (like, shuffle) at the ends, previous / play / next in the middle.
                val cy = y0 + s + 12 * d + 22 * d
                val slotW = (r - l) / 5
                var customSeen = 0
                a.actions.forEachIndexed { i, act ->
                    val k = when (act.role) {
                        "prev" -> 1
                        "play" -> 2
                        "next" -> 3
                        else -> if (customSeen++ == 0) 0 else 4
                    }
                    val cx = l + slotW * (k + 0.5f)
                    when (act.role) {
                        "play" -> glyph(c, if (a.playing) "pause" else "play", cx, cy, 30 * d)
                        "prev", "next" -> glyph(c, act.role, cx, cy, 22 * d)
                        else -> act.icon?.let { ic ->
                            tint.colorFilter = android.graphics.PorterDuffColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
                            rect2.set(cx - 11 * d, cy - 11 * d, cx + 11 * d, cy + 11 * d)
                            c.drawBitmap(ic, null, rect2, tint)
                        }
                    }
                    cardBtns += i to RectF(cx - 24 * d, cy - 24 * d, cx + 24 * d, cy + 24 * d)
                }
                // The song's place: a bar you can drag, with the times either side.
                val py = cy + 22 * d + 6 * d + 10 * d
                val pos = if (seeking) (a.durMs * seekF).toLong()
                else if (a.playing && a.posAt > 0) a.posMs + ((android.os.SystemClock.elapsedRealtime() - a.posAt) * a.speed).toLong() else a.posMs
                val dur = a.durMs
                cardSub.textSize = 12f * d
                cardSub.color = 0xFFA1A7B1.toInt()
                val left = fmtMs(min(pos, if (dur > 0) dur else pos))
                val right = if (dur > 0) fmtMs(dur) else ""
                c.drawText(left, l, baseline(cardSub, py), cardSub)
                val rw = cardSub.measureText(right)
                c.drawText(right, r - rw, baseline(cardSub, py), cardSub)
                val bl = l + cardSub.measureText("00:00") + 10 * d
                val br = r - max(rw, cardSub.measureText("00:00")) - 10 * d
                barRect.set(bl, py - 2.5f * d, br, py + 2.5f * d)
                // Played part and the dot in the song's colour, the rest dark grey: you always see where you are.
                dot.color = 0xFF3A3F47.toInt()
                dot.alpha = 255
                c.drawRoundRect(barRect, 2.5f * d, 2.5f * d, dot)
                if (dur > 0) {
                    val col = accentOf(a.pic ?: cardIcon)
                    dot.color = col
                    dot.alpha = 255
                    val f = (pos.toFloat() / dur).coerceIn(0f, 1f)
                    rect2.set(bl, barRect.top, bl + (br - bl) * f, barRect.bottom)
                    c.drawRoundRect(rect2, 2.5f * d, 2.5f * d, dot)
                    c.drawCircle(bl + (br - bl) * f, py, (if (seeking) 8f else 6f) * d, dot)
                }
                if (a.playing || seeking) postInvalidateDelayed(if (seeking) 16 else 500)
            }
            "timer" -> {
                val isz = 20 * d
                cardIcon?.let { drawPic(c, it, l, y0, isz, true, "") }
                cardSub.textSize = 13f * d
                cardSub.color = 0xFFA1A7B1.toInt()
                c.drawText(a.title.ifBlank { a.text }, l + isz + 8 * d, baseline(cardSub, y0 + isz / 2), cardSub)
                cardTitle.textSize = 40f * d
                val big = actBig(a)
                c.drawText(big, l, y0 + 22 * d + 44 * d, cardTitle)
                drawPills(c, a, l, r, y0 + 22 * d + 50 * d + 8 * d)
                if (a.chronoBase > 0) postInvalidateDelayed(500)
            }
            else -> {
                val isCall = a.kind == "call"
                val s = (if (isCall) 60 else 54) * d
                drawPic(c, if (isCall) a.pic else cardIcon, l, y0, s, isCall, a.title)
                val second = when {
                    isCall && a.callType == 2 && a.chronoBase > 0 -> actBig(a)
                    else -> a.text
                }
                drawHead(c, l + s + 12 * d, r, y0, s, a.title, second, if (isCall && a.callType == 2) 0xFF34D399.toInt() else 0xFFA1A7B1.toInt(), big = isCall)
                drawPills(c, a, l, r, y0 + s + 14 * d)
                if (isCall) postInvalidateDelayed(500)
            }
        }
        c.restore()
    }

    /** The big value: a running timer counts live; anything else as the app wrote it. */
    private fun actBig(a: Live): String {
        if (a.chronoBase <= 0L) return a.big
        val secs = (abs(System.currentTimeMillis() - a.chronoBase) / 1000L)
        return if (secs >= 3600) "%d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60) else "%d:%02d".format(secs / 60, secs % 60)
    }

    /** A live activity: icon, then the big value (green), the label right of the camera, a thin progress line. */
    private fun drawAct(c: Canvas) {
        val a = act ?: return
        val ch = compactHeight().toFloat()
        val (lw, rw) = compactExtent()
        rect.set(camX - lw, 0f, camX + rw, ch)
        c.drawRoundRect(rect, ch / 2, ch / 2, bg)
        val now = System.currentTimeMillis()
        val sig = a.key + a.right
        if (sig != actShown) {
            actShown = sig
            actSince = now
        }
        val cy = ch / 2
        val isz = ch * 0.62f
        val ix = rect.left + ch * 0.28f
        if (a.kind == "media") {
            val art = a.pic ?: actIcon
            if (art != null) {
                c.save()
                rect2.set(ix, cy - isz / 2, ix + isz, cy + isz / 2)
                path.reset()
                path.addRoundRect(rect2, isz * 0.28f, isz * 0.28f, Path.Direction.CW)
                c.clipPath(path)
                bmp.alpha = 255
                c.drawBitmap(art, null, rect2, bmp)
                c.restore()
                artEdge(c, rect2, isz * 0.28f)
            }
            drawWave(c, rect.right - ch / 2 - 13 * d, cy, ch * 0.55f, accentOf(art), a.playing)
            return
        }
        actIcon?.let { bm ->
            c.save()
            path.reset()
            path.addCircle(ix + isz / 2, cy, isz / 2, Path.Direction.CW)
            c.clipPath(path)
            rect2.set(ix, cy - isz / 2, ix + isz, cy + isz / 2)
            c.drawBitmap(bm, null, rect2, null)
            c.restore()
        } ?: run {
            dot.color = 0xFF34D399.toInt()
            dot.alpha = 255
            c.drawCircle(ix + isz / 2, cy, 3 * d, dot)
        }
        minute.color = if (a.kind == "media") Color.WHITE else 0xFF34D399.toInt()
        minute.alpha = 255
        val bigL = ix + isz + 5 * d
        marquee(c, actBig(a), minute, bigL, camX - gap() - 2 * d, baseline(minute, cy), actSince)
        minute.color = Color.WHITE
        line.color = 0xFFE5E7EB.toInt()
        line.alpha = 255
        marquee(c, a.right, line, camX + gap(), rect.right - ch / 2, baseline(line, cy), actSince)
        line.color = 0xFFC8C8C8.toInt()
        if (a.progress in 0f..1f) {
            dot.color = 0xFF34D399.toInt()
            dot.alpha = 220
            val pl = rect.left + ch / 2
            val pr = rect.right - ch / 2
            c.drawRect(pl, ch - 2.5f * d, pr, ch - 1f * d, dim)
            c.drawRect(pl, ch - 2.5f * d, pl + (pr - pl) * a.progress, ch - 1f * d, dot)
        }
        val moving = minute.measureText(actBig(a)) > camX - gap() - bigL || line.measureText(a.right) > rect.right - ch / 2 - camX - gap()
        if (moving) postInvalidateDelayed(40) else if (a.chronoBase > 0L) postInvalidateDelayed(500)
    }

    private val path = Path()

    /** The running weather animation (kept between frames). */
    private var fx: WeatherFx? = null
    private var fxSince = 0L

    /**
     * An event played inside the notch, at the notch's height (a tap opens it big).
     * Goal: the team colour washes across and back, a light runs round the edge, "هدف!" writes
     * itself, then the scorer's name. News: the notch flips over to the headline.
     */
    private fun drawInline(c: Canvas, m: Match, b: Burst) {
        val ch = compactHeight().toFloat()
        val (lw, rw) = compactExtent()
        val (il, ir) = inlineExtent() ?: (lw to rw)
        val l = lerp(camX - lw, camX - il, t)
        val r = lerp(camX + rw, camX + ir, t)
        val age = System.currentTimeMillis() - b.startedAt
        val cy = ch / 2
        val shake = if (b.kind == "red" && age < 450) (4 * d * sin(age / 22.0) * kotlin.math.exp(-age / 180.0)).toFloat() else 0f
        c.save()
        c.translate(shake, 0f)
        rect.set(l, 0f, r, ch)

        if (b.kind == "news") {
            // The notch flips over (first half of the move), then grows a line under the camera.
            if (t < 0.5f) {
                val flip = abs(kotlin.math.cos(t * Math.PI)).toFloat().coerceAtLeast(0.04f)
                c.scale(1f, flip, 0f, cy)
                c.drawRoundRect(rect, ch / 2, ch / 2, bg)
                // The front: the match if there is one (news on a quiet notch has a blank front).
                if (m.id >= 0) drawCompact(c, m, ch, 1f)
            } else {
                val grow = ease((t - 0.5f) * 2f)
                pillWithRow(c, l, r, ch, grow)
                drawInlineNews(c, b, l, r, ch, grow)
            }
            c.restore()
            return
        }

        val grow = if (hasRow(b)) ease((age - 1100) / 350f) else 0f
        pillWithRow(c, l, r, ch, grow)
        if (grow > 0f) {
            // Who scored (or got the card), on its own line under the camera: nothing hides it.
            val old = line.textSize
            line.textSize = 12f * d
            line.color = Color.WHITE
            line.alpha = (255 * ((grow - 0.4f) / 0.6f).coerceIn(0f, 1f)).toInt()
            val txt = rowText(b)
            val y = ch + rowHeight() * grow / 2 - 1 * d
            if (line.measureText(txt) <= r - l - ch) {
                line.textAlign = Paint.Align.CENTER
                c.drawText(txt, (l + r) / 2, baseline(line, y), line)
                line.textAlign = Paint.Align.LEFT
            } else {
                marquee(c, txt, line, l + ch / 2, r - ch / 2, baseline(line, y), b.startedAt + 1500)
            }
            line.textSize = old
            line.color = 0xFFC8C8C8.toInt()
            line.alpha = 255
            if (grow < 1f) postInvalidateOnAnimation()
        }
        rect.set(l, 0f, r, ch)
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
        val word = words[0]
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
            val areaL = l + ch / 2
            val tw = min(title.measureText(word), wordRight - areaL)
            // Written from the right, like Arabic handwriting; a long name then slides to show the rest.
            c.save()
            c.clipRect(wordRight - tw * reveal - 2 * d, 0f, wordRight + 2 * d, ch)
            marquee(c, word, title, areaL, wordRight, baseline(title, cy), b.startedAt)
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
            // Round the whole shape, the line under the camera included.
            rect.set(l, 0f, r, ch + rowHeight() * grow)
            rect.inset(inset, inset)
            path.reset()
            val bot = lerp(ch / 2, 14 * d, grow) - inset
            val top = ch / 2 - inset
            path.addRoundRect(rect, floatArrayOf(top, top, top, top, bot, bot, bot, bot), Path.Direction.CW)
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

    /** The notch with a second line hanging under the camera ([grow] 0..1): round on top, softer below. */
    private fun pillWithRow(c: Canvas, l: Float, r: Float, ch: Float, grow: Float) {
        val h = ch + rowHeight() * grow
        if (grow <= 0f) {
            rect.set(l, 0f, r, ch)
            c.drawRoundRect(rect, ch / 2, ch / 2, bg)
            return
        }
        val top = ch / 2
        val bottom = lerp(ch / 2, 14 * d, grow)
        path.reset()
        rect.set(l, 0f, r, h)
        path.addRoundRect(rect, floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom), Path.Direction.CW)
        c.drawPath(path, bg)
    }

    /**
     * News inside the notch: on the camera's line the crest and "أخبار برشلونة", the headline on its
     * own line under the camera (no letter hides behind it), gliding through once if it's long.
     */
    private fun drawInlineNews(c: Canvas, b: Burst, l: Float, r: Float, ch: Float, grow: Float) {
        val cy = ch / 2
        val crest = ch - 12 * d
        val cx = l + ch / 2 - 2 * d
        val icon = b.icon
        if (icon != null) {
            rect.set(cx, cy - crest / 2, cx + crest, cy + crest / 2)
            c.drawBitmap(icon, null, rect, bmp)
        } else if (b.logo != null) {
            drawCrest(c, b.logo, cx, cy - crest / 2, crest, 1f)
        }
        small.color = 0xFF60A5FA.toInt()
        small.alpha = 255
        val oldSmall = small.textSize
        small.textSize = 10.5f * d
        val labelL = cx + crest + 6 * d
        val labelR = camX - gap() - 2 * d
        if (labelR - labelL > 12 * d) {
            small.textAlign = Paint.Align.LEFT
            c.drawText(TextUtils.ellipsize(b.title, small, labelR - labelL, TextUtils.TruncateAt.END).toString(), labelL, baseline(small, cy), small)
        }
        small.textSize = oldSmall
        small.color = 0xFF9CA3AF.toInt()
        if (grow <= 0.3f) return
        val old = line.textSize
        line.textSize = 12f * d
        line.color = Color.WHITE
        line.alpha = (255 * ((grow - 0.3f) / 0.7f).coerceIn(0f, 1f)).toInt()
        val y = ch + rowHeight() * grow / 2 - 1 * d
        marquee(c, b.sub, line, l + ch / 2, r - ch / 2, baseline(line, y), b.startedAt + 900)
        line.textSize = old
        line.color = 0xFFC8C8C8.toInt()
        line.alpha = 255
        if (grow < 1f) postInvalidateOnAnimation()
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
        val tp = tpl()
        if (tp == "B" && !landscape) {
            if (t == 0f) lastTpl = "B"
            drawB(c, m, ch, a)
            return
        }
        if (t == 0f && !landscape) lastTpl = "A"
        fitA()
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
            // 1.9.1: the breathing is slow (~3 s), 10 frames a second look the same.
            postInvalidateDelayed(100)
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
