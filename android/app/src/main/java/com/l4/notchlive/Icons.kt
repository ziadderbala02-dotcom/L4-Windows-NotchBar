package com.l4.notchlive

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Small stroke icons drawn in code (24×24 grid, like the design). */
@SuppressLint("ViewConstructor")
class Icon(ctx: Context, private val name: String, sizeDp: Int = 22) : View(ctx) {
    var color: Int = Ui.MUTED
        set(v) {
            field = v
            invalidate()
        }

    private val px = (sizeDp * ctx.resources.displayMetrics.density).toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val r = RectF()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(px, px)

    override fun onDraw(c: Canvas) {
        val s = width / 24f
        c.save()
        c.scale(s, s)
        p.color = color
        p.strokeWidth = 2f
        p.style = Paint.Style.STROKE
        path.reset()
        when (name) {
            "home" -> {
                path.moveTo(3f, 11f); path.lineTo(12f, 4f); path.lineTo(21f, 11f)
                path.moveTo(5f, 10f); path.lineTo(5f, 20f); path.lineTo(19f, 20f); path.lineTo(19f, 10f)
                c.drawPath(path, p)
            }
            "ball" -> {
                c.drawCircle(12f, 12f, 9f, p)
                path.moveTo(12f, 7f); path.lineTo(16f, 10f); path.lineTo(14.5f, 15f); path.lineTo(9.5f, 15f); path.lineTo(8f, 10f); path.close()
                c.drawPath(path, p)
            }
            "battery" -> {
                r.set(3f, 7f, 19f, 17f)
                c.drawRoundRect(r, 2f, 2f, p)
                c.drawLine(21f, 11f, 21f, 13f, p)
                c.drawLine(7f, 10f, 7f, 14f, p)
                c.drawLine(10f, 10f, 10f, 14f, p)
            }
            "gear" -> {
                c.drawCircle(12f, 12f, 3f, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val x1 = 12 + 6.5 * Math.cos(a)
                    val y1 = 12 + 6.5 * Math.sin(a)
                    val x2 = 12 + 9.5 * Math.cos(a)
                    val y2 = 12 + 9.5 * Math.sin(a)
                    c.drawLine(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), p)
                }
                c.drawCircle(12f, 12f, 6.5f, p)
            }
            "pin" -> {
                path.moveTo(9f, 3f); path.lineTo(15f, 3f); path.lineTo(14f, 9f); path.lineTo(18f, 12f); path.lineTo(18f, 14f)
                path.lineTo(6f, 14f); path.lineTo(6f, 12f); path.lineTo(10f, 9f); path.close()
                c.drawPath(path, p)
                c.drawLine(12f, 14f, 12f, 21f, p)
            }
            "search" -> {
                c.drawCircle(11f, 11f, 7f, p)
                c.drawLine(20f, 20f, 16f, 16f, p)
            }
            "back" -> {
                path.moveTo(15f, 6f); path.lineTo(9f, 12f); path.lineTo(15f, 18f)
                c.drawPath(path, p)
            }
            "chevron" -> {
                path.moveTo(15f, 6f); path.lineTo(9f, 12f); path.lineTo(15f, 18f)
                c.drawPath(path, p)
            }
            "info" -> {
                c.drawCircle(12f, 12f, 9f, p)
                c.drawLine(12f, 11f, 12f, 17f, p)
                c.drawPoint(12f, 7.5f, p)
            }
            "down" -> {
                c.drawLine(12f, 4f, 12f, 16f, p)
                path.moveTo(7f, 11f); path.lineTo(12f, 16f); path.lineTo(17f, 11f)
                c.drawPath(path, p)
                c.drawLine(5f, 20f, 19f, 20f, p)
            }
            "bolt" -> {
                r.set(3f, 7f, 19f, 17f)
                c.drawRoundRect(r, 2f, 2f, p)
                c.drawLine(21f, 11f, 21f, 13f, p)
                path.moveTo(11f, 9f); path.lineTo(9f, 12f); path.lineTo(13f, 12f); path.lineTo(11f, 15f)
                c.drawPath(path, p)
            }
        }
        c.restore()
    }
}
