package com.l4.notchlive

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The app's look (dark or light, following the phone unless picked) and small helpers to
 * build screens in code. The notch itself is always black.
 */
object Ui {
    var dark = true
        private set

    // Colours of the current theme.
    var BG = 0; private set
    var CARD = 0; private set
    var CARD2 = 0; private set
    var LINE = 0; private set
    var TEXT = 0; private set
    var MUTED = 0; private set
    var NAV = 0; private set
    var ACCENT = 0; private set
    var ON_ACCENT = 0; private set
    var ACCENT_SOFT = 0; private set
    var AMBER = 0; private set
    var AMBER_SOFT = 0; private set
    var BLUE = 0; private set
    var BLUE_SOFT = 0; private set
    var RED = 0; private set

    /** Picks the theme from the setting and the phone's dark mode. */
    fun load(ctx: Context) {
        dark = when (Prefs(ctx).theme) {
            "light" -> false
            "dark" -> true
            else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        if (dark) {
            BG = 0xFF0A0B0F.toInt(); CARD = 0xFF15171E.toInt(); CARD2 = 0xFF1C1F28.toInt(); LINE = 0xFF262833.toInt()
            TEXT = 0xFFF4F4F5.toInt(); MUTED = 0xFFA1A1AA.toInt(); NAV = 0xFF111318.toInt()
            ACCENT = 0xFF34D399.toInt(); ON_ACCENT = 0xFF052E1C.toInt(); ACCENT_SOFT = 0xFF10251D.toInt()
            AMBER = 0xFFFBBF24.toInt(); AMBER_SOFT = 0xFF2A2310.toInt(); BLUE = 0xFF60A5FA.toInt(); BLUE_SOFT = 0xFF14213A.toInt()
            RED = 0xFFF87171.toInt()
        } else {
            BG = 0xFFF5F6F8.toInt(); CARD = 0xFFFFFFFF.toInt(); CARD2 = 0xFFF0F1F4.toInt(); LINE = 0xFFE4E5EA.toInt()
            TEXT = 0xFF111318.toInt(); MUTED = 0xFF5B5E6B.toInt(); NAV = 0xFFFFFFFF.toInt()
            ACCENT = 0xFF047857.toInt(); ON_ACCENT = 0xFFFFFFFF.toInt(); ACCENT_SOFT = 0xFFDDF5EA.toInt()
            AMBER = 0xFFB45309.toInt(); AMBER_SOFT = 0xFFFDF0D5.toInt(); BLUE = 0xFF1D4ED8.toInt(); BLUE_SOFT = 0xFFE0EAFD.toInt()
            RED = 0xFFB91C1C.toInt()
        }
    }

    /** System bars in the theme's colours. */
    @Suppress("DEPRECATION")
    fun bars(a: Activity) {
        a.window.statusBarColor = BG
        a.window.navigationBarColor = NAV
        if (Build.VERSION.SDK_INT >= 30) {
            val light = if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            a.window.insetsController?.setSystemBarsAppearance(
                light,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
    }

    /** Arabic-friendly face: the system's bold/regular sans. */
    val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radius: Float, stroke: Int? = null, strokeW: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (stroke != null) setStroke(strokeW, stroke)
        }

    fun Context.text(s: String, size: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            typeface = if (bold) BOLD else REGULAR
            textDirection = View.TEXT_DIRECTION_LOCALE
        }

    /** A rounded button: filled accent, or quiet outline. Always ≥ 44 dp tall. */
    fun Context.button(s: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = s
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = BOLD
            setTextColor(if (primary) ON_ACCENT else TEXT)
            background = if (primary) rounded(ACCENT, dp(14).toFloat()) else rounded(Color.TRANSPARENT, dp(14).toFloat(), LINE, dp(1))
            minHeight = dp(44)
            setPadding(dp(16), 0, dp(16), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    fun Context.column(pad: Int = 0): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    fun Context.row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun Context.card(pad: Int = 16): LinearLayout = column(pad).apply {
        background = rounded(CARD, dp(20).toFloat())
    }

    /** A section title above a card, small and muted. */
    fun Context.label(s: String): TextView = text(s, 12f, MUTED, bold = true)

    fun Context.crest(url: String, size: Int = 28): ImageView = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        scaleType = ImageView.ScaleType.FIT_CENTER
        val iv = this
        Logos.get(url) { Logos.get(url) {}?.let { iv.setImageBitmap(it) } }?.let { setImageBitmap(it) }
    }

    /** A thin divider line inside a card. */
    fun Context.divider(): View = View(this).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
    }

    fun lp(w: Int, h: Int, weight: Float = 0f, top: Int = 0, start: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply {
            topMargin = top
            marginStart = start
        }

    const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    fun View.gone(hide: Boolean) {
        visibility = if (hide) View.GONE else View.VISIBLE
    }
}
