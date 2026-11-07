package com.l4.notchlive

import android.annotation.SuppressLint
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.min

/**
 * A grid of cells you rearrange with your finger: long-press a cell, it lifts under the finger,
 * drag it and the others make room (or, in [swap] mode, it trades places with the cell it's
 * dropped on). While editing, cells show their "×" (views tagged "x").
 *
 * [onMove] gets (from, to) on drop; the caller saves and redraws.
 */
@SuppressLint("ViewConstructor")
class DragGrid(
    ctx: Context,
    private val columns: Int,
    private val gapPx: Int,
    private val swap: Boolean,
    private val onMove: (Int, Int) -> Unit,
    private val onEditing: (Boolean) -> Unit = {},
) : ViewGroup(ctx) {
    /** Cell order: position → child index. */
    private val order = ArrayList<Int>()
    private var cellW = 0
    private var cellH = 0
    private var dragging: View? = null
    private var from = -1
    private var at = -1
    private var grabX = 0f
    private var grabY = 0f
    private var lastX = 0f
    private var lastY = 0f

    var editing = false
        set(v) {
            field = v
            for (i in 0 until childCount) showX(getChildAt(i), v)
        }

    private fun showX(v: View, on: Boolean) {
        v.findViewWithTag<View>("x")?.visibility = if (on) View.VISIBLE else View.GONE
    }

    /** Adds a cell; a long press on it starts editing and lifts it. */
    fun addCell(v: View) {
        order += childCount
        addView(v)
        showX(v, editing)
        v.isLongClickable = true
        v.setOnLongClickListener {
            if (!editing) {
                editing = true
                onEditing(true)
            }
            lift(v)
            true
        }
    }

    private fun lift(v: View) {
        dragging = v
        from = order.indexOf(indexOfChild(v))
        at = from
        grabX = lastX - v.x
        grabY = lastY - v.y
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        v.bringToFront()
        v.animate().scaleX(1.08f).scaleY(1.08f).alpha(0.92f).setDuration(120).start()
        v.elevation = 12f
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        cellW = (w - gapPx * (columns - 1)) / columns
        cellH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            cellH = max(cellH, c.measuredHeight)
        }
        for (i in 0 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY))
        }
        val rows = (childCount + columns - 1) / columns
        setMeasuredDimension(w, max(0, rows * cellH + (rows - 1) * gapPx))
    }

    /** Left edge of a column; mirrored in right-to-left layouts (Arabic reads from the right). */
    private fun cellX(pos: Int): Int {
        val col = pos % columns
        val c = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) columns - 1 - col else col
        return c * (cellW + gapPx)
    }

    private fun cellY(pos: Int) = (pos / columns) * (cellH + gapPx)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        order.forEachIndexed { pos, child ->
            val c = getChildAt(child) ?: return@forEachIndexed
            if (c === dragging) return@forEachIndexed
            val x = cellX(pos)
            val y = cellY(pos)
            c.layout(x, y, x + cellW, y + cellH)
            c.translationX = 0f
            c.translationY = 0f
        }
    }

    /** The cell position under a point. */
    private fun posAt(x: Float, y: Float): Int {
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        var col = (x / (cellW + gapPx)).toInt().coerceIn(0, columns - 1)
        if (rtl) col = columns - 1 - col
        val row = (y / (cellH + gapPx)).toInt().coerceAtLeast(0)
        return min(order.size - 1, row * columns + col)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        lastX = e.x
        lastY = e.y
        return dragging != null && e.actionMasked == MotionEvent.ACTION_MOVE
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        lastX = e.x
        lastY = e.y
        val v = dragging ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                v.x = e.x - grabX
                v.y = e.y - grabY
                val p = posAt(e.x, e.y)
                if (!swap && p != at && p >= 0) {
                    // The others slide over to make room.
                    val child = order.removeAt(at)
                    order.add(p, child)
                    at = p
                    order.forEachIndexed { pos, ci ->
                        val c = getChildAt(ci)
                        if (c !== v) c.animate().x(cellX(pos).toFloat()).y(cellY(pos).toFloat()).setDuration(150).start()
                    }
                } else if (swap) {
                    at = p
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drop()
        }
        return true
    }

    private fun drop() {
        val v = dragging ?: return
        val f = from
        val t = at
        dragging = null
        v.elevation = 0f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
        parent?.requestDisallowInterceptTouchEvent(false)
        if (f >= 0 && t >= 0 && f != t) {
            onMove(f, t)
        } else {
            requestLayout()
        }
    }

    /** Ends a drag that never moved (the long press only): cells stay, "×" stays shown. */
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(e)
        if (dragging != null && (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)) drop()
        return handled
    }
}
