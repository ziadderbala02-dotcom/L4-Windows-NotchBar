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

    private class Geo(val cx: Float, val cy: Float, val r: Float, val screenW: Int, val portrait: Boolean)

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
        // The punch hole: a small cut-out at the top, closest to the middle.
        val hole = cut.filter { it.top <= sb && it.width() < screenW / 3 }.minByOrNull { abs(it.centerX() - screenW / 2) }
        return if (hole != null) {
            Geo(hole.exactCenterX(), hole.exactCenterY(), min(hole.width(), hole.height()) / 2f, screenW, screenH >= screenW)
        } else {
            Geo(screenW / 2f, sb / 2f, sb * 0.28f, screenW, screenH >= screenW)
        }
    }

    private var big = false

    /**
     * Draw [match] (null = hide). [burst] or [info] widen it; [tucked] leaves only a small mark.
     * [clock] is the minute label, [added] the announced added time ("+4" or "").
     */
    fun update(match: Match?, clock: String, added: String, burst: Burst?, info: Boolean, tucked: Boolean) {
        if (match == null) {
            hide()
            return
        }
        val v = view ?: attach() ?: return
        v.match = match
        v.clock = clock
        v.added = added
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
        if (!big && anim?.isRunning != true) layout()
        applyVisibility()
        v.invalidate()
    }

    /**
     * Full screen (status bar hidden): shown on top by default; with the option off, the window
     * stays (to hear when the bar comes back) but draws nothing and lets touches through.
     */
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
        if (!g.portrait) return null
        val v = IslandView(h, gestures)
        v.camR = g.r
        v.scale = prefs.scale / 100f
        // The room between the status bar icons (measured on Omar's Redmi, with a spare icon each side).
        v.leftMax = g.cx - g.screenW * LEFT_EDGE
        v.rightMax = g.screenW * RIGHT_EDGE - g.cx
        v.setOnApplyWindowInsetsListener { _, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                statusBarVisible = ins.isVisible(WindowInsets.Type.statusBars())
                applyVisibility()
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
    private fun layout() {
        val v = view ?: return
        val p = lp ?: return
        val g = geo ?: return
        val density = ctx.resources.displayMetrics.density
        val ch = v.compactHeight()
        val width: Int
        val height: Int
        val x: Int
        if (big) {
            width = v.bigWidth(g.screenW)
            height = max(ch, v.bigHeight())
            x = (g.cx - width / 2f).toInt()
            v.camX = g.cx - x
        } else if (v.tucked) {
            width = v.tuckWidth()
            height = ch
            x = (g.cx + g.r + 2 * density).toInt()
            v.camX = g.cx - x
        } else {
            val (lw, rw) = v.compactExtent()
            width = (lw + rw).toInt() + 1
            height = ch
            x = (g.cx - lw).toInt()
            v.camX = g.cx - x
        }
        val y = (g.cy - ch / 2f + prefs.offsetY * density).toInt()
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
