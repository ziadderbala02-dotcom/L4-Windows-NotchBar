package com.l4.notchlive

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Puts [IslandView] on screen in its own small window over the camera.
 * With the optional accessibility service on, the window sits above the status bar and
 * takes taps; otherwise it's a normal overlay (needs "display over other apps").
 */
class Island(private val ctx: Context, private val prefs: Prefs, private val gestures: IslandView.Gestures) {
    companion object {
        /** Status bar room on Omar's phone: left icons end at 27% of the width, right icons start at 69%. */
        const val LEFT_EDGE = 195f / 720f
        const val RIGHT_EDGE = 496f / 720f

        /**
         * 1.8: the free room in the status bar, as fractions of the width from the camera's centre.
         * Xiaomi/Redmi/POCO were measured on Omar's phone; other brands put notification icons nearer
         * the middle (an Honor left 16%), so they get a narrower default (24%). The width slider scales it.
         */
        fun edges(cxFrac: Float): Pair<Float, Float> {
            val b = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
            return if (b.contains("xiaomi") || b.contains("redmi") || b.contains("poco")) {
                LEFT_EDGE to RIGHT_EDGE
            } else {
                (cxFrac - 0.12f) to (cxFrac + 0.12f)
            }
        }

        /** What the last measure found, for the device page. */
        var lastReport = ""
    }

    private var view: IslandView? = null
    private var wm: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null
    private var host: Context? = null
    private var anim: ValueAnimator? = null
    private var shownBurst: Burst? = null

    /** False while an app hides the status bar (full-screen video, games). */
    var statusBarVisible = true
        private set

    /** The notch is on screen and visible right now (else alerts go to a notification). */
    val showing get() = view != null && (statusBarVisible || prefs.overFullscreen)

    /** [r] half the camera's height, [hw] half its width (a notch is wider than tall), [sb] status bar height. */
    private class Geo(val cx: Float, val cy: Float, val r: Float, val screenW: Int, val portrait: Boolean, val hw: Float = r, val sb: Int = 0)

    private var geo: Geo? = null

    /** Where to draw: the accessibility service when it's on, else a plain overlay. */
    private fun pickHost(): Pair<Context, Int>? {
        TapService.instance?.let { return it to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY }
        if (Settings.canDrawOverlays(ctx)) return ctx to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        return null
    }

    fun canShow(): Boolean = pickHost() != null

    fun portrait(): Boolean = ctx.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    private fun measure(w: WindowManager): Geo {
        val res = ctx.resources
        val density = res.displayMetrics.density
        val sbId = res.getIdentifier("status_bar_height", "dimen", "android")
        val sb = if (sbId > 0) res.getDimensionPixelSize(sbId) else (24 * density).toInt()
        val screenW: Int
        val screenH: Int
        var cut: List<Rect> = emptyList()
        if (Build.VERSION.SDK_INT >= 30) {
            val m = w.currentWindowMetrics
            screenW = m.bounds.width()
            screenH = m.bounds.height()
            cut = m.windowInsets.displayCutout?.boundingRects ?: emptyList()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            w.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels
            screenH = dm.heightPixels
            if (Build.VERSION.SDK_INT >= 29) {
                @Suppress("DEPRECATION")
                cut = w.defaultDisplay.cutout?.boundingRects ?: emptyList()
            }
        }
        if (screenW > screenH) {
            // Landscape: the camera is on a side; the pill goes to the top centre instead.
            return Geo(screenW / 2f, 6 * density, 0f, screenW, false, 0f, sb)
        }
        // The punch hole: a small cut-out at the top, closest to the middle.
        val hole = cut.filter { it.top <= sb && it.width() < screenW / 3 }.minByOrNull { abs(it.centerX() - screenW / 2) }
        val dx = 0f
        val g = if (hole != null) {
            // The real cut-out: a punch hole is round, a notch/teardrop is wider than tall (1.8).
            Geo(hole.exactCenterX() + dx, hole.exactCenterY(), min(hole.width(), hole.height()) / 2f, screenW, screenH >= screenW, hole.width() / 2f, sb)
        } else {
            Geo(screenW / 2f + dx, sb / 2f, sb * 0.28f, screenW, screenH >= screenW, sb * 0.28f, sb)
        }
        val shape = when {
            hole == null -> "none"
            hole.width() > hole.height() * 1.4f -> "notch"
            hole.exactCenterX() < screenW * 0.35f || hole.exactCenterX() > screenW * 0.65f -> "corner"
            else -> "hole"
        }
        val (le, re) = edges(g.cx / screenW)
        lastReport = "screen ${screenW}x$screenH px · density ${"%.2f".format(density)} · status bar ${sb}px (${"%.1f".format(sb / density)}dp)\n" +
            "camera: $shape ${hole?.let { "${it.width()}x${it.height()} at ${it.left},${it.top}" } ?: "-"} · cutouts ${cut.size}\n" +
            "room: ${(le * 100).toInt()}%..${(re * 100).toInt()}% · fit ${"%.2f".format(fitFor(sb, density))}"
        return g
    }

    private var big = false

    /** 1.8: the notch at 100% is ~92% of the status bar's height, whatever the phone's display size. */
    private fun fitFor(sb: Int, density: Float): Float = min(1f, sb * 0.92f / (28f * density * 1.1f)).coerceAtLeast(0.5f)

    private fun density() = ctx.resources.displayMetrics.density

    /** Saver mode: the view skips endless animations. */
    var calm = false
        set(v) {
            field = v
            view?.calm = v
        }

    /** The big view's content changed size (stats arrived): resize the window. */
    fun relayout() {
        if (big) layout()
        view?.invalidate()
    }

    /**
     * Draw [match] (null = hide). [burst] or [info] widen it; [tucked] leaves only a small mark.
     * [clock] is the minute label, [added] the announced added time ("+4" or "").
     */
    fun update(
        match: Match?, clock: String, added: String, burst: Burst?, info: Boolean, tucked: Boolean, circle: Boolean,
        others: Int = 0, idle: Boolean = false, stats: List<Stat>? = null, rec: Long = 0L,
        idleText: String = "", idleTemp: String = "", weather: String = "", weatherUntil: Long = 0L, weatherAlways: Boolean = false,
        weatherGlyph: String = "", weatherWord: String = "", act: Live? = null, actIcon: android.graphics.Bitmap? = null,
        ringAct: Live? = null, card: Live? = null, cardIcon: android.graphics.Bitmap? = null,
        idleTone: Int = 0, idleAlert: Boolean = false,
    ) {
        if (match == null) {
            hide()
            return
        }
        val v = view ?: attach() ?: return
        v.others = others
        v.idle = idle
        val actGrew = v.act?.right != act?.right || v.act?.big != act?.big || (v.act == null) != (act == null)
        v.act = act
        val ringGrew = (v.ringAct == null) != (ringAct == null) || (v.match?.id ?: -1) != match.id
        v.ringAct = ringAct
        val cardGrew = (v.card?.actions?.size ?: -1) != (card?.actions?.size ?: -1) || v.card?.kind != card?.kind
        v.card = card
        v.cardIcon = cardIcon
        if (cardGrew && big) post { layout() }
        if (ringGrew && !big) post { layout() }
        v.actIcon = actIcon
        if (actGrew && !big) layout()
        v.idleK = prefs.idleSize / 100f
        v.inlineEvents = !prefs.bigEvents
        val grew = v.idleText != idleText || v.idleTemp != idleTemp
        v.idleText = idleText
        v.idleTemp = idleTemp
        v.idleTone = idleTone
        v.idleAlert = idleAlert
        v.weather = weather
        v.weatherUntil = weatherUntil
        v.weatherAlways = weatherAlways
        v.weatherGlyph = weatherGlyph
        v.weatherWord = weatherWord
        v.idleScale = prefs.idleScale / 100f
        if (grew && !big) layout()
        v.stretch = 0f
        v.tplPref = prefs.template
        v.recSince = rec
        if (v.infoStats?.size != stats?.size) {
            v.infoStats = stats
            if (big) layout()
        } else {
            v.infoStats = stats
        }
        v.roomy = roomy()
        v.match = match
        v.clock = clock
        v.added = added
        val wasCircle = v.circle
        v.circle = circle && !v.landscape
        val wantBig = burst != null || info
        if (burst !== shownBurst || wantBig != big) {
            shownBurst = burst
            if (wantBig) {
                v.burst = burst
                v.info = info && burst == null
                v.tucked = tucked
                big = true
                layout()
                animateTo(1f) {}
            } else if (big) {
                big = false
                animateTo(0f) {
                    v.burst = null
                    v.info = false
                    v.tucked = tucked
                    layout()
                }
            }
        } else if (!big) {
            v.tucked = tucked
        }
        if (wasCircle != v.circle && !big) v.t = 0f
        if (!big && anim?.isRunning != true) layout()
        applyVisibility()
        v.invalidate()
    }

    /**
     * Full screen (status bar hidden): shown on top by default; with the option off, the window
     * stays (to hear when the bar comes back) but draws nothing and lets touches through.
     */
    /** Full screen with the "bigger in full screen" option: nothing to cover, so no squeezing. */
    // Only sideways (a video or a game): telling a hidden status bar apart in portrait isn't reliable on HyperOS.
    private fun roomy() = prefs.roomyFullscreen && prefs.overFullscreen && !portrait()

    private fun applyVisibility() {
        val v = view ?: return
        val p = lp ?: return
        val visible = statusBarVisible || prefs.overFullscreen
        v.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        val flags = if (visible) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (flags != p.flags) {
            p.flags = flags
            try {
                wm?.updateViewLayout(v, p)
            } catch (e: Exception) {
                view = null
            }
        }
    }

    private fun animateTo(target: Float, end: () -> Unit) {
        val v = view ?: return
        anim?.cancel()
        anim = ValueAnimator.ofFloat(v.t, target).apply {
            duration = if (target > v.t) 420 else 320
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { v.t = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled) end()
                }
            })
            start()
        }
    }

    private fun attach(): IslandView? {
        val (h, type) = pickHost() ?: return null
        val w = h.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val g = measure(w)
        val v = IslandView(h, gestures)
        v.calm = calm
        v.camR = g.r
        v.camHW = if (g.portrait) max(g.r, g.hw) else g.r
        v.sbH = g.sb.toFloat()
        v.fit = fitFor(g.sb, density())
        v.scale = prefs.scale / 100f
        v.landscape = !g.portrait
        v.tplPref = prefs.template
        if (g.portrait) {
            // The room between the status bar icons, from the camera outwards, times the width slider
            // (100% = up to the icons). Measured on Omar's Redmi; narrower by default on other brands.
            val (le, re) = edges(g.cx / g.screenW)
            val f = prefs.widthPct / 100f
            v.leftMax = max(0f, g.cx - g.screenW * le) * f
            v.rightMax = max(0f, g.screenW * re - g.cx) * f
            // 1.9.1: "auto" picks the layout from the phone's full room, so the width slider only
            // narrows the notch and never flips it to another layout (or moves it under the bar).
            v.fullLeft = max(0f, g.cx - g.screenW * le)
            v.fullRight = max(0f, g.screenW * re - g.cx)
        }
        v.belowMax = g.screenW * 0.62f * prefs.widthPct / 100f
        v.fullBelow = g.screenW * 0.62f
        v.setOnApplyWindowInsetsListener { _, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val was = statusBarVisible
                statusBarVisible = ins.isVisible(WindowInsets.Type.statusBars())
                applyVisibility()
                if (was != statusBarVisible) {
                    v.roomy = roomy()
                    if (!big) layout()
                    v.invalidate()
                }
            }
            ins
        }
        val p = WindowManager.LayoutParams(
            1, 1, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.LEFT
        p.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= 30) p.fitInsetsTypes = 0
        p.title = "NotchLive"
        try {
            w.addView(v, p)
        } catch (e: Exception) {
            return null
        }
        view = v
        wm = w
        lp = p
        host = h
        geo = g
        shownBurst = null
        big = false
        return v
    }

    /** Size and place the window for the current mode; tells the view where the camera is. */
    /** The last tap landed on the ride's circle (the left one). */
    fun tapOnRide(): Boolean = view?.let { it.ringAct != null && it.touchX < it.compactHeight() } ?: false

    private fun post(f: () -> Unit) = view?.post(f)

    private fun layout() {
        val v = view ?: return
        val p = lp ?: return
        val g = geo ?: return
        val density = ctx.resources.displayMetrics.density
        val ch = v.compactHeight()
        val width: Int
        val height: Int
        val x: Int
        val inline = if (big) v.inlineExtent() else null
        if (inline != null) {
            // An event playing inside the notch: it grows only where its text needs room.
            width = (inline.first + inline.second).toInt() + 1
            height = v.inlineHeight()
            x = (g.cx - inline.first).toInt()
            v.camX = g.cx - x
        } else if (big) {
            width = v.bigWidth(g.screenW)
            height = max(ch, v.bigHeight())
            x = (g.cx - width / 2f).toInt()
            v.camX = g.cx - x
        } else if (v.circle) {
            // Left of the phone's own pill, in the gap after the clock (its right end can reach the icons).
            // A ride gets its own circle there, with the match's next to it.
            width = v.circleWidth()
            height = ch
            x = (g.screenW * LEFT_EDGE + 2 * density).toInt()
            v.camX = g.cx - x
        } else if (v.tucked) {
            width = v.tuckWidth()
            height = ch
            x = (g.cx + g.r + 2 * density).toInt()
            v.camX = g.cx - x
        } else if (v.below()) {
            // 1.8, templates C and D: a pill just under the status bar, centred on the camera.
            val (bw, bh) = v.belowSize()
            width = bw
            height = bh
            x = (g.cx - bw / 2f).toInt()
            v.camX = g.cx - x
        } else {
            val (lw, rw) = v.compactExtent()
            // An activity's circle left of the match (the scoreboard keeps its place round the camera).
            val side = v.sideW()
            width = (lw + rw + side).toInt() + 1
            height = ch
            x = (g.cx - lw - side).toInt()
            v.camX = g.cx - x
        }
        // The always-on notch has its own up/down unless linked (1.8).
        val off = if (v.idle && !big && !prefs.linkPos) prefs.idleOffsetY else prefs.offsetY
        val y = when {
            !g.portrait -> g.cy.toInt()
            !big && !v.circle && !v.tucked && v.below() -> (g.sb + 3 * density + off * density).toInt()
            else -> (g.cy - ch / 2f + off * density).toInt()
        }
        if (p.width == width && p.height == height && p.x == x && p.y == y) return
        p.width = width
        p.height = height
        p.x = x
        p.y = y
        try {
            wm?.updateViewLayout(v, p)
        } catch (e: Exception) {
            // The window went away (host disconnected); the next update re-attaches.
            view = null
        }
    }

    fun hide() {
        anim?.cancel()
        val v = view ?: return
        try {
            wm?.removeViewImmediate(v)
        } catch (e: Exception) {
            // Already gone.
        }
        view = null
        wm = null
        lp = null
        host = null
        shownBurst = null
        big = false
    }

    /** Host changed (accessibility on/off), rotation, or size settings: build the window again. */
    fun reattach() {
        hide()
        statusBarVisible = true
    }
}
