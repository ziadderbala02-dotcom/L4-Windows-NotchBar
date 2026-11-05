package com.l4.notchlive

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.l4.notchlive.Ui.MATCH
import com.l4.notchlive.Ui.WRAP
import com.l4.notchlive.Ui.column
import com.l4.notchlive.Ui.crest
import com.l4.notchlive.Ui.dp
import com.l4.notchlive.Ui.lp
import com.l4.notchlive.Ui.row
import com.l4.notchlive.Ui.text
import java.util.concurrent.Executors

/** The card that drops from the notch: score, events timeline, stats. Refreshes every 15 s. */
class MatchActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var id = 0
    private var tab = 0
    private var match: Match? = null
    private var stats: List<Stat> = emptyList()
    private lateinit var header: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var body: LinearLayout

    private val reload = object : Runnable {
        override fun run() {
            load()
            main.postDelayed(this, 15_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getIntExtra("id", 0)
        window.setGravity(Gravity.TOP)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.45f)

        val card = column(16).apply {
            background = Ui.rounded(Ui.CARD, dp(24).toFloat())
            // Home on the left, like the notch.
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        header = column()
        card.addView(header)
        tabs = row().apply { gravity = Gravity.CENTER }
        card.addView(tabs, lp(MATCH, WRAP, top = dp(12)))
        body = column()
        val scroll = ScrollView(this).apply { addView(body) }
        val maxH = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        card.addView(scroll, lp(MATCH, WRAP, top = dp(10)))
        scroll.viewTreeObserver.addOnGlobalLayoutListener {
            if (scroll.height > maxH) scroll.layoutParams = scroll.layoutParams.apply { height = maxH }
        }

        val outer = LinearLayout(this).apply {
            setPadding(dp(8), dp(36), dp(8), dp(8))
            addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        setContentView(outer)
        header.addView(text("بيحمّل…", 13f, Ui.MUTED))
        drawTabs()
    }

    override fun onResume() {
        super.onResume()
        main.post(reload)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(reload)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        id = intent.getIntExtra("id", id)
        match = null
        stats = emptyList()
        load()
    }

    private fun load() {
        val gid = id
        io.execute {
            val m = try {
                Api.game(gid)
            } catch (e: Exception) {
                null
            }
            val s = if (m != null && m.state != Api.PRE) {
                try {
                    Api.stats(gid, m.home.id)
                } catch (e: Exception) {
                    emptyList()
                }
            } else emptyList()
            main.post {
                if (gid != id || isFinishing) return@post
                if (m != null) match = m
                if (s.isNotEmpty()) stats = s
                draw()
            }
        }
    }

    private fun draw() {
        val m = match ?: return
        header.removeAllViews()
        header.addView(text(m.comp, 11f, Ui.MUTED).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))
        val r = row()
        r.addView(team(m.home), lp(0, WRAP, 1f))
        val mid = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        mid.addView(text("${m.home.score} - ${m.away.score}", 30f, bold = true))
        val clockColor = if (m.state == Api.LIVE) Ui.ACCENT else Ui.MUTED
        val clock = if (m.state == Api.PRE) android.text.format.DateFormat.format("EEE h:mm a", m.kickoff).toString() else m.clock
        mid.addView(text(clock, 12f, clockColor).apply { gravity = Gravity.CENTER })
        r.addView(mid, lp(WRAP, WRAP))
        r.addView(team(m.away), lp(0, WRAP, 1f))
        header.addView(r, lp(MATCH, WRAP, top = dp(6)))
        drawBody()
    }

    private fun team(s: Side): View = column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(crest(s.logo, 44))
        addView(text(s.short, 13f, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
        }, lp(MATCH, WRAP, top = dp(4)))
    }

    private fun drawTabs() {
        tabs.removeAllViews()
        listOf("الأحداث", "الإحصائيات").forEachIndexed { i, name ->
            val on = i == tab
            tabs.addView(text(name, 13f, if (on) Color.BLACK else Ui.TEXT, bold = on).apply {
                background = Ui.rounded(if (on) Ui.ACCENT else Ui.LINE, dp(14).toFloat())
                setPadding(dp(16), dp(6), dp(16), dp(6))
                setOnClickListener {
                    tab = i
                    drawTabs()
                    drawBody()
                }
            }, lp(WRAP, WRAP, start = if (i > 0) dp(8) else 0))
        }
    }

    private fun drawBody() {
        body.removeAllViews()
        val m = match ?: return
        if (tab == 0) {
            val evs = m.events.filter { it.kind != "other" }.sortedByDescending { it.at }
            if (evs.isEmpty()) body.addView(text(if (m.state == Api.PRE) "الماتش لسه مبدأش" else "مفيش أحداث لسه", 13f, Ui.MUTED).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP, top = dp(8)))
            for (e in evs) body.addView(eventRow(e), lp(MATCH, WRAP, top = dp(6)))
        } else {
            if (stats.isEmpty()) body.addView(text("مفيش إحصائيات لسه", 13f, Ui.MUTED).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP, top = dp(8)))
            for (s in stats) body.addView(statRow(s, m), lp(MATCH, WRAP, top = dp(10)))
        }
    }

    private fun icon(e: Ev): String = when (e.kind) {
        "goal" -> "⚽"
        "yellow" -> "🟨"
        "red" -> "🟥"
        "sub" -> "🔁"
        "var" -> "📺"
        "pen" -> "🎯"
        else -> "•"
    }

    private fun eventRow(e: Ev): View {
        val r = row().apply { gravity = if (e.home) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.END or Gravity.CENTER_VERTICAL }
        val what = when (e.kind) {
            "goal" -> (e.player ?: "جون") + when {
                e.own -> " (في مرماه)"
                e.pen -> " (جزاء)"
                else -> ""
            }
            "sub" -> "⬆ ${e.player ?: "?"}  ⬇ ${e.extra ?: "?"}"
            "var", "pen" -> listOfNotNull(e.label.ifBlank { null }, e.player).joinToString(" • ")
            else -> e.player ?: e.label
        }
        val minute: TextView = text(e.minute, 12f, Ui.MUTED)
        val desc: TextView = text("${icon(e)}  $what", 13f)
        if (e.home) {
            r.addView(minute, lp(WRAP, WRAP))
            r.addView(desc, lp(WRAP, WRAP, start = dp(8)))
        } else {
            r.addView(desc, lp(WRAP, WRAP))
            r.addView(minute, lp(WRAP, WRAP, start = dp(8)))
        }
        return r
    }

    private fun statRow(s: Stat, m: Match): View {
        val box = column()
        val top = row()
        top.addView(text(s.home, 13f, bold = true), lp(0, WRAP, 1f))
        top.addView(text(s.name, 12f, Ui.MUTED).apply { gravity = Gravity.CENTER }, lp(WRAP, WRAP))
        top.addView(text(s.away, 13f, bold = true).apply { gravity = Gravity.END }, lp(0, WRAP, 1f))
        box.addView(top)
        val bar = row()
        val share = s.homeShare.coerceIn(0.02f, 0.98f)
        bar.addView(View(this).apply { background = Ui.rounded(m.home.color, dp(3).toFloat()) }, lp(0, dp(6), share))
        bar.addView(View(this), lp(dp(3), dp(6)))
        bar.addView(View(this).apply { background = Ui.rounded(m.away.color, dp(3).toFloat()) }, lp(0, dp(6), 1f - share))
        box.addView(bar, lp(MATCH, WRAP, top = dp(4)))
        return box
    }
}
