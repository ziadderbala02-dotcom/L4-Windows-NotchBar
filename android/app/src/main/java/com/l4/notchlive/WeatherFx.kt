package com.l4.notchlive

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The weather inside the always-on notch: the same particles, speeds, colours and gradients as
 * the approved preview page, measured in its units ([u] = the notch height / 30) and stepped per
 * frame at 60 fps. Drawn inside the pill (the caller clips), under the text.
 */
class WeatherFx(val kind: String) {
    private class P(var x: Float, var y: Float, var v: Float, var a: Float = 0f, var b: Float = 0f)

    private val ps = ArrayList<P>()
    private var l = 0f
    private var r = 0f
    private var h = 0f
    private var u = 1f
    private var last = 0L
    private var flash = 0f
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private fun rnd(a: Float, b: Float) = a + Random.nextFloat() * (b - a)

    /** Preview's vertical frame: notch top = 0, centre = 15u, bottom = 30u. */
    private val cy get() = 15 * u

    private fun seed() {
        ps.clear()
        when (kind) {
            "rain" -> repeat(46) { ps += P(rnd(l, r), rnd(-29 * u, 31 * u), rnd(2.2f, 3.4f) * u) }
            "storm" -> repeat(70) { ps += P(rnd(l, r), rnd(-29 * u, 31 * u), rnd(3.5f, 5f) * u) }
            "snow" -> repeat(36) { ps += P(rnd(l, r), rnd(-9 * u, 31 * u), rnd(0.25f, 0.6f) * u, rnd(0.8f, 1.9f) * u, rnd(0f, 6f)) }
            "clouds" -> repeat(4) { i -> ps += P(l + i * 60 * u, 0f, rnd(0.12f, 0.25f) * u, rnd(0.8f, 1.25f)) }
            "wind" -> repeat(12) { ps += P(rnd(l, r), rnd(cy - 11 * u, cy + 11 * u), rnd(3f, 5.5f) * u, rnd(14f, 32f) * u) }
            // 1.9: cold (a few slow flakes over frost), heat (rising shimmer), dust (a brown drift).
            "cold" -> repeat(16) { ps += P(rnd(l, r), rnd(-9 * u, 31 * u), rnd(0.15f, 0.35f) * u, rnd(0.6f, 1.3f) * u, rnd(0f, 6f)) }
            "hot" -> repeat(7) { i -> ps += P(l + (r - l) * (i + 0.5f) / 7f, rnd(0f, 30 * u), rnd(0.25f, 0.45f) * u, rnd(10f, 18f) * u, rnd(0f, 6f)) }
            "dust" -> repeat(60) { ps += P(rnd(l, r), rnd(0f, 30 * u), rnd(2.5f, 5f) * u, rnd(0.5f, 1.3f) * u) }
        }
    }

    /** Draws one frame for the notch spanning [left]..[right], [height] tall. */
    fun draw(c: Canvas, left: Float, right: Float, height: Float, now: Long) {
        if (left != l || right != r || height != h || ps.isEmpty() && kind in setOf("rain", "storm", "snow", "clouds", "wind", "cold", "hot", "dust")) {
            l = left; r = right; h = height; u = height / 30f
            seed()
        }
        // Steps scale with the real frame time, so a slow frame doesn't slow the weather.
        val k = if (last == 0L) 1f else ((now - last) / 16.67f).coerceIn(0.2f, 3f)
        last = now
        when (kind) {
            "rain" -> rain(c, k, storm = false)
            "storm" -> rain(c, k, storm = true)
            "snow" -> snow(c, k)
            "sun" -> sun(c, now)
            "clouds" -> clouds(c, k)
            "wind" -> wind(c, k)
            "fog" -> fog(c, now)
            "cold" -> cold(c, k, now)
            "hot" -> hot(c, k, now)
            "dust" -> dust(c, k, now)
        }
    }

    private fun rain(c: Canvas, k: Float, storm: Boolean) {
        if (storm) {
            if (Random.nextFloat() < 0.012f * k) flash = 1f
            if (flash > 0f) {
                fill.shader = null
                fill.color = Color.argb((flash * 0.55f * 255).toInt(), 220, 230, 255)
                c.drawRect(l, 0f, r, h, fill)
                if (flash > 0.7f) {
                    line.color = Color.argb((flash * 255).toInt(), 255, 255, 230)
                    line.strokeWidth = 1.6f * u
                    val x = l + (r - l) * 0.3f
                    path.reset()
                    path.moveTo(x, cy - 15 * u); path.lineTo(x - 5 * u, cy - 3 * u); path.lineTo(x + 2 * u, cy - 2 * u); path.lineTo(x - 4 * u, cy + 12 * u)
                    c.drawPath(path, line)
                }
                flash -= 0.06f * k
            }
        }
        line.color = if (storm) Color.argb(178, 150, 170, 220) else Color.argb(191, 140, 180, 255)
        line.strokeWidth = 1.2f * u
        val drift = if (storm) 0.6f else 0.25f
        val dx = if (storm) 3.5f else 1.6f
        val dy = if (storm) 7f else 6f
        for (p in ps) {
            p.y += p.v * k
            p.x -= p.v * drift * k
            if (p.y > cy + 18 * u) {
                p.y = rnd(-21 * u, cy - 16 * u)
                p.x = rnd(l, r + (if (storm) 40 else 20) * u)
            }
            c.drawLine(p.x, p.y, p.x + dx * u, p.y - dy * u, line)
        }
    }

    private fun snow(c: Canvas, k: Float) {
        fill.shader = null
        fill.color = Color.argb(230, 255, 255, 255)
        for (p in ps) {
            p.y += p.v * k
            p.b += 0.03f * k
            if (p.y > cy + 17 * u) {
                p.y = cy - 17 * u
                p.x = rnd(l, r)
            }
            c.drawCircle(p.x + sin(p.b) * 2.5f * u, p.y, p.a, fill)
        }
    }

    private fun sun(c: Canvas, now: Long) {
        val x = l + 34 * u
        val pulse = 0.8f + 0.2f * sin(now / 700.0).toFloat()
        fill.shader = RadialGradient(
            x, cy, 70 * u * pulse,
            intArrayOf(Color.argb(217, 255, 196, 77), Color.argb(71, 255, 150, 40), Color.argb(0, 255, 150, 40)),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(l, 0f, r, h, fill)
        fill.shader = null
        line.color = Color.argb(140, 255, 210, 120)
        line.strokeWidth = 1.4f * u
        val base = now / 4000.0
        for (i in 0 until 8) {
            val a = base + i * PI / 4
            c.drawLine(x + cos(a).toFloat() * 9 * u, cy + sin(a).toFloat() * 9 * u, x + cos(a).toFloat() * 13 * u, cy + sin(a).toFloat() * 13 * u, line)
        }
        fill.color = 0xFFFFCF5A.toInt()
        c.drawCircle(x, cy, 6 * u, fill)
    }

    private fun clouds(c: Canvas, k: Float) {
        fill.shader = null
        fill.color = Color.argb(97, 200, 210, 230)
        for (p in ps) {
            p.x += p.v * k
            if (p.x > r + 30 * u) p.x = l - 40 * u
            val s = p.a
            c.drawCircle(p.x, cy + 2 * u * s, 9 * u * s, fill)
            c.drawCircle(p.x + 9 * u * s, cy - 2 * u * s, 11 * u * s, fill)
            c.drawCircle(p.x + 20 * u * s, cy + 2 * u * s, 8 * u * s, fill)
        }
    }

    private fun wind(c: Canvas, k: Float) {
        line.strokeWidth = 1.3f * u
        for (p in ps) {
            p.x += p.v * k
            if (p.x - p.a > r) {
                p.x = l - rnd(0f, 40 * u)
                p.y = rnd(cy - 11 * u, cy + 11 * u)
            }
            line.shader = LinearGradient(p.x - p.a, 0f, p.x, 0f, Color.argb(0, 255, 255, 255), Color.argb(178, 230, 240, 255), Shader.TileMode.CLAMP)
            path.reset()
            path.moveTo(p.x - p.a, p.y)
            path.quadTo(p.x - p.a / 2, p.y - 2 * u, p.x, p.y)
            c.drawPath(path, line)
        }
        line.shader = null
    }

    private fun fog(c: Canvas, now: Long) {
        val span = 240 * u
        for (k in 0 until 3) {
            val off = ((now / (30.0 + k * 12)) * u % span).toFloat()
            val a = ((0.16f + k * 0.05f) * 255).toInt()
            fill.shader = LinearGradient(
                l - span + off, 0f, l + off, 0f,
                intArrayOf(Color.argb(0, 220, 225, 235), Color.argb(a, 220, 225, 235), Color.argb(0, 220, 225, 235)),
                null, Shader.TileMode.MIRROR,
            )
            val top = cy - 15 * u + k * 8 * u
            c.drawRect(l, top, r, top + 12 * u, fill)
        }
        fill.shader = null
    }

    /** Frost creeping in from both ends, and a few slow flakes. */
    private fun cold(c: Canvas, k: Float, now: Long) {
        val breathe = 0.75f + 0.25f * sin(now / 900.0).toFloat()
        val w = 46 * u
        val ice = Color.argb((150 * breathe).toInt(), 150, 205, 255)
        val clear = Color.argb(0, 150, 205, 255)
        fill.shader = LinearGradient(l, 0f, l + w, 0f, ice, clear, Shader.TileMode.CLAMP)
        c.drawRect(l, 0f, l + w, h, fill)
        fill.shader = LinearGradient(r - w, 0f, r, 0f, clear, ice, Shader.TileMode.CLAMP)
        c.drawRect(r - w, 0f, r, h, fill)
        fill.shader = null
        // Tiny frost crystals on the edges.
        line.color = Color.argb((170 * breathe).toInt(), 210, 235, 255)
        line.strokeWidth = 0.8f * u
        for (x in listOf(l + 10 * u, r - 10 * u)) {
            for (i in 0 until 3) {
                val a = i * PI / 3
                val dx = (cos(a) * 4 * u).toFloat()
                val dy = (sin(a) * 4 * u).toFloat()
                c.drawLine(x - dx, cy - dy, x + dx, cy + dy, line)
            }
        }
        fill.color = Color.argb(220, 235, 245, 255)
        for (p in ps) {
            p.y += p.v * k
            p.b += 0.025f * k
            if (p.y > cy + 17 * u) {
                p.y = cy - 17 * u
                p.x = rnd(l, r)
            }
            c.drawCircle(p.x + sin(p.b) * 3f * u, p.y, p.a, fill)
        }
    }

    /** Warm glow at the bottom edge and wavy lines of hot air rising. */
    private fun hot(c: Canvas, k: Float, now: Long) {
        val pulse = 0.7f + 0.3f * sin(now / 800.0).toFloat()
        fill.shader = LinearGradient(
            0f, h, 0f, 0f,
            intArrayOf(Color.argb((150 * pulse).toInt(), 255, 120, 30), Color.argb((45 * pulse).toInt(), 255, 170, 60), Color.argb(0, 255, 170, 60)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(l, 0f, r, h, fill)
        fill.shader = null
        line.strokeWidth = 1.1f * u
        for (p in ps) {
            p.y -= p.v * k
            p.b += 0.08f * k
            if (p.y < -p.a) p.y = h + p.a
            val fade = (p.y / h).coerceIn(0f, 1f)
            line.color = Color.argb((120 * fade).toInt(), 255, 190, 110)
            path.reset()
            var y = p.y + p.a / 2
            path.moveTo(p.x, y)
            for (i in 1..6) {
                val ny = p.y + p.a / 2 - i * p.a / 6
                path.lineTo(p.x + sin(p.b + i * 1.3f) * 2.2f * u, ny)
                y = ny
            }
            c.drawPath(path, line)
        }
    }

    /** A brownish haze sliding sideways with grains of dust. */
    private fun dust(c: Canvas, k: Float, now: Long) {
        val span = 200 * u
        val off = ((now / 14.0) * u % span).toFloat()
        fill.shader = LinearGradient(
            l - span + off, 0f, l + off, 0f,
            intArrayOf(Color.argb(60, 190, 140, 80), Color.argb(130, 205, 155, 90), Color.argb(60, 190, 140, 80)),
            null, Shader.TileMode.MIRROR,
        )
        c.drawRect(l, 0f, r, h, fill)
        fill.shader = null
        for (p in ps) {
            p.x += p.v * k
            p.y += sin((now / 300.0) + p.x / (20 * u)).toFloat() * 0.3f * u * k
            if (p.x > r) {
                p.x = l - rnd(0f, 20 * u)
                p.y = rnd(0f, h)
            }
            fill.color = Color.argb(200, 215, 170, 110)
            c.drawCircle(p.x, p.y, p.a, fill)
        }
    }

    companion object {
        /** Small weather icons drawn in code (emoji fonts differ, some phones show squares). */
        fun icon(c: Canvas, kind: String, cx: Float, cy: Float, s: Float) {
            val f = Paint(Paint.ANTI_ALIAS_FLAG)
            val st = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
            fun cloud(color: Int, dy: Float = 0f) {
                f.color = color
                c.drawCircle(cx - s * 0.22f, cy + dy + s * 0.05f, s * 0.22f, f)
                c.drawCircle(cx + s * 0.05f, cy + dy - s * 0.08f, s * 0.28f, f)
                c.drawCircle(cx + s * 0.28f, cy + dy + s * 0.07f, s * 0.19f, f)
                c.drawRect(cx - s * 0.22f, cy + dy + s * 0.05f, cx + s * 0.28f, cy + dy + s * 0.26f, f)
            }
            when (kind) {
                "sun" -> {
                    f.color = 0xFFFFCF5A.toInt()
                    c.drawCircle(cx, cy, s * 0.24f, f)
                    st.color = 0xFFFFCF5A.toInt()
                    st.strokeWidth = s * 0.08f
                    for (i in 0 until 8) {
                        val a = i * PI / 4
                        c.drawLine(cx + cos(a).toFloat() * s * 0.36f, cy + sin(a).toFloat() * s * 0.36f, cx + cos(a).toFloat() * s * 0.48f, cy + sin(a).toFloat() * s * 0.48f, st)
                    }
                }
                "clouds" -> cloud(0xFFD7DEEA.toInt())
                "rain", "storm" -> {
                    cloud(0xFFC3CCDC.toInt(), -s * 0.12f)
                    if (kind == "rain") {
                        st.color = 0xFF8CB4FF.toInt()
                        st.strokeWidth = s * 0.07f
                        for (i in -1..1) c.drawLine(cx + i * s * 0.2f, cy + s * 0.26f, cx + i * s * 0.2f - s * 0.06f, cy + s * 0.44f, st)
                    } else {
                        f.color = 0xFFFFD84D.toInt()
                        val p = Path()
                        p.moveTo(cx + s * 0.04f, cy + s * 0.12f); p.lineTo(cx - s * 0.12f, cy + s * 0.34f); p.lineTo(cx + s * 0.02f, cy + s * 0.34f)
                        p.lineTo(cx - s * 0.06f, cy + s * 0.52f); p.lineTo(cx + s * 0.16f, cy + s * 0.26f); p.lineTo(cx + s * 0.02f, cy + s * 0.26f); p.close()
                        c.drawPath(p, f)
                    }
                }
                "snow" -> {
                    st.color = Color.WHITE
                    st.strokeWidth = s * 0.07f
                    for (i in 0 until 3) {
                        val a = i * PI / 3
                        c.drawLine(cx - cos(a).toFloat() * s * 0.4f, cy - sin(a).toFloat() * s * 0.4f, cx + cos(a).toFloat() * s * 0.4f, cy + sin(a).toFloat() * s * 0.4f, st)
                    }
                }
                "fog" -> {
                    st.color = 0xFFC8CEDA.toInt()
                    st.strokeWidth = s * 0.08f
                    for (i in -1..1) c.drawLine(cx - s * 0.38f + (i + 1) * s * 0.05f, cy + i * s * 0.2f, cx + s * 0.38f - (1 - i) * s * 0.05f, cy + i * s * 0.2f, st)
                }
                "wind" -> {
                    st.color = 0xFFE6F0FF.toInt()
                    st.strokeWidth = s * 0.08f
                    val p = Path()
                    p.moveTo(cx - s * 0.42f, cy - s * 0.12f); p.cubicTo(cx, cy - s * 0.12f, cx + s * 0.3f, cy - s * 0.18f, cx + s * 0.22f, cy - s * 0.34f)
                    p.moveTo(cx - s * 0.42f, cy + s * 0.1f); p.lineTo(cx + s * 0.3f, cy + s * 0.1f)
                    p.moveTo(cx - s * 0.3f, cy + s * 0.3f); p.lineTo(cx + s * 0.1f, cy + s * 0.3f)
                    c.drawPath(p, st)
                }
                "night" -> {
                    f.color = 0xFFE8E2C8.toInt()
                    val p = Path()
                    p.addCircle(cx, cy, s * 0.32f, Path.Direction.CW)
                    val cut = Path()
                    cut.addCircle(cx + s * 0.16f, cy - s * 0.12f, s * 0.28f, Path.Direction.CW)
                    p.op(cut, Path.Op.DIFFERENCE)
                    c.drawPath(p, f)
                }
            }
        }
    }
}
