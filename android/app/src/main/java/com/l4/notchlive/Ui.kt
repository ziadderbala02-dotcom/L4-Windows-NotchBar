package com.l4.notchlive

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Small helpers to build the screens in code (no XML layouts, no libraries). */
object Ui {
    const val BG = 0xFF0B0B0F.toInt()
    const val CARD = 0xFF17171D.toInt()
    const val LINE = 0xFF2A2A33.toInt()
    const val TEXT = 0xFFF4F4F5.toInt()
    const val MUTED = 0xFF9CA3AF.toInt()
    const val ACCENT = 0xFF34D399.toInt()

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
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    fun Context.button(s: String, primary: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            text = s
            isAllCaps = false
            textSize = 13f
            setTextColor(if (primary) Color.BLACK else TEXT)
            background = rounded(if (primary) ACCENT else LINE, dp(10).toFloat())
            minHeight = dp(38)
            minimumHeight = dp(38)
            setPadding(dp(14), 0, dp(14), 0)
            stateListAnimator = null
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

    fun Context.card(): LinearLayout = column(14).apply {
        background = rounded(CARD, dp(16).toFloat())
    }

    fun Context.crest(url: String, size: Int = 28): ImageView = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        scaleType = ImageView.ScaleType.FIT_CENTER
        val iv = this
        Logos.get(url) { Logos.get(url) {}?.let { iv.setImageBitmap(it) } }?.let { setImageBitmap(it) }
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
