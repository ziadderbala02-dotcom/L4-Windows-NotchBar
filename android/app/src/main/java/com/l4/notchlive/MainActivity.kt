package com.l4.notchlive

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.l4.notchlive.Ui.MATCH
import com.l4.notchlive.Ui.WRAP
import com.l4.notchlive.Ui.button
import com.l4.notchlive.Ui.card
import com.l4.notchlive.Ui.column
import com.l4.notchlive.Ui.crest
import com.l4.notchlive.Ui.divider
import com.l4.notchlive.Ui.dp
import com.l4.notchlive.Ui.label
import com.l4.notchlive.Ui.lp
import com.l4.notchlive.Ui.row
import com.l4.notchlive.Ui.text
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The app: a first-run setup (permissions step by step), then four tabs — Home, Matches,
 * Usage, Settings — following the approved 1.4 design. Built in code; Arabic (فصحى) or
 * English; light or dark.
 */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)

    private lateinit var root: FrameLayout
    private var tab = 0
    /** null = a tab; else a sub-page ("position", "teams"). */
    private var page: String? = null
    private var step = 0
    private var onboarding = false

    // Matches tab state.
    private var filter = "live"
    private var query = ""
    private var loaded: List<Match>? = null
    private var loadError = false

    // Update banner state.
    private var release: Updater.Release? = null
    private var downloading = false
    private var dlNote = ""
    private var dlPct = -1

    private val tick = object : Runnable {
        override fun run() {
            if (!onboarding && page == null && tab == 0) drawHome()
            main.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        L.load(this)
        Ui.load(this)
        Ui.bars(this)
        tab = savedInstanceState?.getInt("tab") ?: intent.getIntExtra("tab", 0)
        step = savedInstanceState?.getInt("step") ?: 0
        root = FrameLayout(this).apply {
            setBackgroundColor(Ui.BG)
            layoutDirection = if (L.ar) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        setContentView(root)
        onboarding = !prefs.onboarded || savedInstanceState?.getBoolean("onboarding") == true
        checkUpdate()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("tab", tab)
        out.putInt("step", step)
        out.putBoolean("onboarding", onboarding)
    }

    override fun onResume() {
        super.onResume()
        if (prefs.enabled) LiveService.start(this, LiveService.ACTION_REFRESH)
        if (onboarding) {
            // Came back from a settings page: move on if it's granted.
            if (step in 1..4 && step != 3 && granted(step)) step++
            drawOnboarding()
        } else {
            draw()
        }
        main.removeCallbacks(tick)
        main.postDelayed(tick, 5000)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            page != null -> {
                page = null
                draw()
            }
            !onboarding && tab != 0 -> {
                tab = 0
                draw()
            }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    /* ======================= frame: content + bottom bar ======================= */

    private fun draw() {
        if (onboarding) {
            drawOnboarding()
            return
        }
        root.removeAllViews()
        val frame = column()
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = column().apply { setPadding(dp(20), dp(20), dp(20), dp(24)) }
        scroll.addView(content)
        frame.addView(scroll, lp(MATCH, 0, 1f))
        when (page) {
            "position" -> positionPage(content)
            "teams" -> teamsPage(content)
            else -> when (tab) {
                0 -> home(content)
                1 -> matches(content)
                2 -> usage(content)
                else -> settings(content)
            }
        }
        if (page == null) frame.addView(navBar())
        root.addView(frame, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun navBar(): View {
        val bar = row().apply {
            setBackgroundColor(Ui.NAV)
            setPadding(dp(8), dp(8), dp(8), dp(12))
        }
        val items = listOf("home" to "tab_home", "ball" to "tab_matches", "battery" to "tab_usage", "gear" to "tab_settings")
        items.forEachIndexed { i, (icon, key) ->
            val on = i == tab
            val color = if (on) Ui.ACCENT else Ui.MUTED
            val item = column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                minimumHeight = dp(52)
                setPadding(0, dp(6), 0, dp(2))
                isClickable = true
                contentDescription = L.t(key)
                setOnClickListener {
                    tab = i
                    page = null
                    draw()
                }
            }
            item.addView(Icon(this, icon).apply { this.color = color })
            item.addView(text(L.t(key), 11f, color, bold = true).apply { gravity = Gravity.CENTER }, lp(WRAP, WRAP, top = dp(3)))
            bar.addView(item, lp(0, WRAP, 1f))
        }
        return column().apply {
            setBackgroundColor(Ui.NAV)
            addView(divider())
            addView(bar, lp(MATCH, WRAP))
        }
    }

    /* ======================= Home ======================= */

    private var homeBox: LinearLayout? = null

    private fun home(c: LinearLayout) {
        // Header: name + status chip.
        val head = row()
        head.addView(logoMark())
        head.addView(text("Notch Live", 22f, bold = true), lp(0, WRAP, 1f, start = dp(10)))
        head.addView(statusChip())
        c.addView(head)
        updateBanner(c)
        homeBox = column()
        c.addView(homeBox, lp(MATCH, WRAP))
        drawHome()
    }

    private fun drawHome() {
        val box = homeBox ?: return
        if (box.parent == null) return
        box.removeAllViews()
        val (now, known) = LiveService.instance?.snapshot() ?: (null to emptyList())

        // In the notch now.
        val live = card()
        val top = row()
        top.addView(text(L.t("now_in_notch"), 13f, Ui.MUTED), lp(0, WRAP, 1f))
        if (now != null) top.addView(text(now.comp, 12f, Ui.MUTED))
        live.addView(top)
        if (now != null) {
            live.addView(miniNotch(now), lp(WRAP, WRAP, top = dp(14)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            val r = row()
            val t = column()
            t.addView(text("${now.home.short}  ${now.home.score} – ${now.away.score}  ${now.away.short}", 16f, bold = true))
            now.events.lastOrNull { it.kind == "goal" }?.let { g ->
                t.addView(text(listOfNotNull(g.player, g.minute).joinToString(" · "), 12f, Ui.MUTED), lp(WRAP, WRAP, top = dp(2)))
            }
            r.addView(t, lp(0, WRAP, 1f))
            r.addView(link(L.t("details")) { startActivity(MatchActivity.intent(this, now)) })
            live.addView(r, lp(MATCH, WRAP, top = dp(14)))
        } else {
            live.addView(text(L.t("nothing_live"), 14f, Ui.MUTED), lp(MATCH, WRAP, top = dp(10)))
        }
        box.addView(live, lp(MATCH, WRAP, top = dp(18)))

        // Next match with a countdown.
        val nowMs = System.currentTimeMillis()
        val next = known.filter { it.state == Api.PRE && it.kickoff > nowMs }.minByOrNull { it.kickoff }
        val nc = card()
        nc.addView(text(L.t("next"), 13f, Ui.MUTED))
        if (next != null) {
            val r = row()
            r.addView(teamBadge(next.home), lp(0, WRAP, 1f))
            val mid = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
            val left = (next.kickoff - nowMs) / 1000
            val d = left / 86400
            val hm = String.format(Locale.US, "%02d:%02d", (left % 86400) / 3600, (left % 3600) / 60)
            mid.addView(text(if (d > 0) "$d${if (L.ar) "ي" else "d"} $hm" else hm, 22f, bold = true).apply { gravity = Gravity.CENTER })
            mid.addView(text(android.text.format.DateFormat.format("EEE h:mm a", next.kickoff).toString(), 12f, Ui.AMBER).apply { gravity = Gravity.CENTER })
            r.addView(mid, lp(WRAP, WRAP))
            r.addView(teamBadge(next.away), lp(0, WRAP, 1f))
            nc.addView(r, lp(MATCH, WRAP, top = dp(14)))
            nc.addView(text(L.t("next_note"), 12f, Ui.MUTED).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP, top = dp(12)))
        } else {
            nc.addView(text(L.t("next_none"), 14f, Ui.MUTED), lp(MATCH, WRAP, top = dp(10)))
        }
        box.addView(nc, lp(MATCH, WRAP, top = dp(12)))

        // Your teams.
        val th = row()
        th.addView(text(L.t("your_teams"), 16f, bold = true), lp(0, WRAP, 1f))
        th.addView(link(L.t("edit")) {
            page = "teams"
            draw()
        })
        box.addView(th, lp(MATCH, WRAP, top = dp(16)))
        val tr = row()
        prefs.teams.take(4).forEachIndexed { i, t ->
            val tc = card(12).apply { gravity = Gravity.CENTER_HORIZONTAL }
            tc.addView(crest(t.logo, 34))
            tc.addView(text(t.name, 13f, bold = true).apply {
                gravity = Gravity.CENTER
                maxLines = 1
            }, lp(MATCH, WRAP, top = dp(6)))
            val playing = known.any { it.state == Api.LIVE && (it.home.id == t.id || it.away.id == t.id) }
            if (playing) tc.addView(text(L.t("live"), 11f, Ui.ACCENT).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))
            tr.addView(tc, lp(0, WRAP, 1f, start = if (i > 0) dp(10) else 0))
        }
        box.addView(tr, lp(MATCH, WRAP, top = dp(6)))
    }

    private fun logoMark(): View {
        val v = FrameLayout(this).apply {
            background = Ui.rounded(0xFF000000.toInt(), dp(9).toFloat(), Ui.ACCENT, dp(2))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val dot = View(this).apply { background = Ui.rounded(Ui.ACCENT, dp(3).toFloat()) }
        v.addView(dot, FrameLayout.LayoutParams(dp(6), dp(6), Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = dp(5) })
        v.layoutParams = LinearLayout.LayoutParams(dp(34), dp(18))
        return v
    }

    private fun statusChip(): View {
        val (key, fg, bg) = when {
            !prefs.enabled || prefs.paused -> Triple("status_off", Ui.MUTED, Ui.CARD2)
            !Settings.canDrawOverlays(this) && TapService.instance == null -> Triple("status_perm", Ui.AMBER, Ui.AMBER_SOFT)
            else -> Triple("status_on", Ui.ACCENT, Ui.ACCENT_SOFT)
        }
        val chip = row().apply {
            background = Ui.rounded(bg, dp(999).toFloat())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            minimumHeight = dp(32)
            isClickable = true
            setOnClickListener {
                // Tapping turns the notch on or off.
                prefs.enabled = !(prefs.enabled && !prefs.paused)
                prefs.paused = false
                if (prefs.enabled) LiveService.start(this@MainActivity) else stopService(Intent(this@MainActivity, LiveService::class.java))
                draw()
            }
        }
        chip.addView(View(this).apply { background = Ui.rounded(fg, dp(4).toFloat()) }, LinearLayout.LayoutParams(dp(7), dp(7)))
        chip.addView(text(L.t(key), 12f, fg, bold = true), lp(WRAP, WRAP, start = dp(6)))
        return chip
    }

    /** A small black notch like the real one, for the home card. */
    private fun miniNotch(m: Match): View {
        val n = row().apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = Ui.rounded(0xFF000000.toInt(), dp(999).toFloat())
            setPadding(dp(12), dp(7), dp(12), dp(7))
        }
        val w = 0xFFFFFFFF.toInt()
        val live = m.state == Api.LIVE && m.phase == "play"
        val clock = if (m.clock == Api.HALF_TIME) L.t("half_time") else m.clock
        n.addView(text(clock.ifBlank { "•" }, 12f, if (live) 0xFF34D399.toInt() else 0xFFFBBF24.toInt(), bold = true))
        n.addView(crest(m.home.logo, 20), lp(dp(20), dp(20), start = dp(8)))
        n.addView(text("${m.home.score}", 15f, w, bold = true), lp(WRAP, WRAP, start = dp(8)))
        n.addView(View(this).apply { background = Ui.rounded(0xFF1F2937.toInt(), dp(8).toFloat()) }, lp(dp(16), dp(16), start = dp(10)))
        n.addView(text("${m.away.score}", 15f, w, bold = true), lp(WRAP, WRAP, start = dp(10)))
        n.addView(crest(m.away.logo, 20), lp(dp(20), dp(20), start = dp(8)))
        n.addView(View(this).apply { background = Ui.rounded(if (live) 0xFF34D399.toInt() else 0xFF6B7280.toInt(), dp(4).toFloat()) }, lp(dp(7), dp(7), start = dp(8)))
        return n
    }

    private fun teamBadge(s: Side): View = column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(crest(s.logo, 44))
        addView(text(s.short, 14f, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
        }, lp(MATCH, WRAP, top = dp(6)))
    }

    private fun link(s: String, onClick: () -> Unit): TextView = text(s, 13f, Ui.ACCENT, bold = true).apply {
        minHeight = dp(44)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        isClickable = true
        setOnClickListener { onClick() }
    }

    /* ======================= Matches ======================= */

    private var listBox: LinearLayout? = null

    private fun matches(c: LinearLayout) {
        c.addView(text(L.t("tab_matches"), 24f, bold = true))
        val search = row().apply {
            background = Ui.rounded(Ui.CARD, dp(14).toFloat())
            setPadding(dp(14), 0, dp(14), 0)
            minimumHeight = dp(48)
        }
        search.addView(Icon(this, "search", 18))
        val box = EditText(this).apply {
            hint = L.t("search_hint")
            setText(query)
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 14f
            background = null
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    query = s?.toString()?.trim() ?: ""
                    drawList()
                }
            })
        }
        search.addView(box, lp(0, WRAP, 1f, start = dp(8)))
        c.addView(search, lp(MATCH, WRAP, top = dp(14)))

        val chips = row()
        listOf("live" to "f_live", "today" to "f_today", "mine" to "f_mine", "pinned" to "f_pinned").forEachIndexed { i, (k, key) ->
            val on = filter == k
            val label = if (k == "pinned" && prefs.pinned.isNotEmpty()) "${L.t(key)} · ${prefs.pinned.size}" else L.t(key)
            chips.addView(text(label, 13f, if (on) Ui.ON_ACCENT else Ui.TEXT, bold = on).apply {
                gravity = Gravity.CENTER
                minHeight = dp(36)
                setPadding(dp(14), 0, dp(14), 0)
                background = if (on) Ui.rounded(Ui.ACCENT, dp(999).toFloat()) else Ui.rounded(0, dp(999).toFloat(), Ui.LINE, dp(1))
                isClickable = true
                setOnClickListener {
                    filter = k
                    loaded = null
                    draw()
                }
            }, lp(WRAP, WRAP, start = if (i > 0) dp(8) else 0))
        }
        c.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }, lp(MATCH, WRAP, top = dp(12)))
        c.addView(text(L.t("pin_note"), 12f, Ui.MUTED), lp(MATCH, WRAP, top = dp(10)))
        listBox = column()
        c.addView(listBox, lp(MATCH, WRAP, top = dp(4)))
        if (loaded == null) load() else drawList()
    }

    private fun load() {
        loadError = false
        listBox?.removeAllViews()
        listBox?.addView(text(L.t("loading"), 13f, Ui.MUTED), lp(MATCH, WRAP, top = dp(12)))
        val f = filter
        io.execute {
            val r = try {
                when (f) {
                    "today" -> Api.today()
                    "mine" -> Api.teamGames(prefs.teams.map { it.id })
                        .filter { it.state != Api.POST || System.currentTimeMillis() - it.kickoff < 86_400_000L }
                    "pinned" -> prefs.pinned.mapNotNull {
                        try {
                            Api.game(it)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    else -> Api.liveNow()
                }
            } catch (e: Exception) {
                null
            }
            main.post {
                if (f != filter) return@post
                loadError = r == null
                loaded = r ?: emptyList()
                drawList()
            }
        }
    }

    private fun drawList() {
        val box = listBox ?: return
        val all = loaded ?: return
        box.removeAllViews()
        if (loadError) {
            box.addView(text(L.t("no_net"), 13f, Ui.RED), lp(MATCH, WRAP, top = dp(12)))
            return
        }
        val pins = prefs.pinned
        val favs = prefs.teams.map { it.id }.toSet()
        val q = query
        val list = all
            .filter { q.isEmpty() || it.home.name.contains(q, true) || it.away.name.contains(q, true) || it.comp.contains(q, true) }
            .sortedWith(compareBy({ it.id !in pins }, { it.comp }, { it.kickoff }))
        if (list.isEmpty()) box.addView(text(L.t("no_matches"), 13f, Ui.MUTED), lp(MATCH, WRAP, top = dp(12)))
        var lastComp = ""
        for (m in list.take(60)) {
            if (m.comp != lastComp) {
                lastComp = m.comp
                box.addView(text(m.comp, 12f, Ui.ACCENT, bold = true), lp(MATCH, WRAP, top = dp(14)))
            }
            box.addView(matchRow(m, m.id in pins, m.home.id in favs || m.away.id in favs), lp(MATCH, WRAP, top = dp(8)))
        }
        if (list.size > 60) box.addView(text(L.f("more_n", list.size - 60), 12f, Ui.MUTED), lp(MATCH, WRAP, top = dp(8)))
    }

    private fun matchRow(m: Match, pinned: Boolean, mine: Boolean): View {
        val r = row().apply {
            background = if (pinned) Ui.rounded(Ui.CARD, dp(16).toFloat(), Ui.ACCENT, dp(2)) else Ui.rounded(Ui.CARD, dp(16).toFloat())
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val teams = column()
        for (home in listOf(true, false)) {
            val s = m.side(home)
            val other = m.side(!home)
            val lose = m.state != Api.PRE && s.score < other.score
            val line = row()
            line.addView(crest(s.logo, 22))
            line.addView(text(s.short, 14f, if (lose) Ui.MUTED else Ui.TEXT, bold = true).apply { maxLines = 1 }, lp(0, WRAP, 1f, start = dp(8)))
            if (m.reds(home) > 0) line.addView(View(this).apply { background = Ui.rounded(0xFFEF4444.toInt(), dp(1).toFloat()) }, lp(dp(6), dp(9), start = dp(6)))
            if (m.state != Api.PRE) line.addView(text("${s.score}", 16f, if (lose) Ui.MUTED else Ui.TEXT, bold = true), lp(WRAP, WRAP, start = dp(8)))
            teams.addView(line, lp(MATCH, WRAP, top = if (home) 0 else dp(8)))
        }
        r.addView(teams, lp(0, WRAP, 1f))
        val side = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val clock = when (m.state) {
            Api.PRE -> android.text.format.DateFormat.format("h:mm a", m.kickoff).toString()
            Api.POST -> L.t("ft")
            else -> if (m.clock == Api.HALF_TIME) L.t("half_time") else m.clock
        }
        val clockColor = when {
            m.state == Api.LIVE && m.phase == "break" -> Ui.AMBER
            m.state == Api.LIVE -> Ui.ACCENT
            else -> Ui.MUTED
        }
        side.addView(text(clock, 13f, clockColor, bold = true).apply { gravity = Gravity.CENTER })
        if (mine) {
            side.addView(text(L.t("your_team"), 10f, Ui.AMBER, bold = true).apply {
                background = Ui.rounded(Ui.AMBER_SOFT, dp(6).toFloat())
                setPadding(dp(6), dp(3), dp(6), dp(3))
            }, lp(WRAP, WRAP, top = dp(8)))
        } else if (m.state != Api.POST) {
            val pinBtn = FrameLayout(this).apply {
                background = if (pinned) Ui.rounded(Ui.ACCENT_SOFT, dp(10).toFloat()) else Ui.rounded(0, dp(10).toFloat(), Ui.LINE, dp(1))
                contentDescription = L.t(if (pinned) "unpin" else "pin")
                isClickable = true
                setOnClickListener {
                    prefs.pinned = if (m.id in prefs.pinned) prefs.pinned - m.id else prefs.pinned + m.id
                    LiveService.start(this@MainActivity, LiveService.ACTION_REFRESH)
                    drawList()
                }
            }
            pinBtn.addView(Icon(this, "pin", 18).apply { color = if (pinned) Ui.ACCENT else Ui.MUTED }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            side.addView(pinBtn, lp(dp(44), dp(36), top = dp(8)))
        }
        r.addView(side, lp(dp(72), WRAP, start = dp(10)))
        return r
    }

    /* ======================= Usage ======================= */

    private fun usage(c: LinearLayout) {
        c.addView(text(L.t("usage"), 24f, bold = true))

        c.addView(text(L.t("speed"), 14f, bold = true), lp(MATCH, WRAP, top = dp(18)))
        c.addView(segmented(listOf("fast" to "m_fast", "balanced" to "m_balanced", "saver" to "m_saver"), prefs.speed) {
            prefs.speed = it
            LiveService.start(this, LiveService.ACTION_REFRESH)
            draw()
        }, lp(MATCH, WRAP, top = dp(10)))
        c.addView(text(L.t("m_${prefs.speed}_d"), 12f, Ui.MUTED), lp(MATCH, WRAP, top = dp(8)))
        if (LiveService.effectiveSpeed(this) != prefs.speed) {
            c.addView(text(L.t("saver_now"), 12f, Ui.AMBER, bold = true), lp(MATCH, WRAP, top = dp(6)))
        }

        // Last match.
        val cards = row().apply { gravity = Gravity.TOP }
        val bytes = prefs.lastBytes
        val dataCard = statCard(
            "down", Ui.BLUE, L.t("data_last"),
            if (bytes >= 0) String.format(Locale.US, "%.1f", bytes / 1_048_576.0) else L.t("no_data_yet"),
            if (bytes >= 0) L.t("mb") else "", L.t("exact"), Ui.ACCENT,
        )
        val bat = prefs.lastBattery
        val batCard = statCard(
            "bolt", Ui.AMBER, L.t("battery_last"),
            when {
                bat == -2 -> "—"
                bat >= 0 -> "~$bat%"
                else -> L.t("no_data_yet")
            },
            "", if (bat == -2) L.t("charging") else L.t("estimate"), Ui.AMBER,
        )
        cards.addView(dataCard, lp(0, WRAP, 1f))
        cards.addView(batCard, lp(0, WRAP, 1f, start = dp(10)))
        c.addView(cards, lp(MATCH, WRAP, top = dp(16)))

        // Last 7 days.
        val week = card()
        val days = (6 downTo 0).map { back ->
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -back) }
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time) to cal
        }
        val daily = prefs.daily
        val values = days.map { (k, _) -> daily[k] ?: 0L }
        val total = values.sum()
        val wh = row()
        wh.addView(text(L.t("week"), 14f, bold = true), lp(0, WRAP, 1f))
        wh.addView(text(String.format(Locale.US, "%.1f %s", total / 1_048_576.0, L.t("mb")), 13f, Ui.MUTED))
        week.addView(wh)
        val bars = row().apply { gravity = Gravity.BOTTOM }
        val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
        days.forEachIndexed { i, (_, cal) ->
            val col = column().apply { gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM }
            val h = (6 + 64 * values[i] / max).toInt()
            val today = i == 6
            col.addView(View(this).apply {
                background = Ui.rounded(if (values[i] == 0L) Ui.LINE else if (today) Ui.ACCENT else Ui.BLUE, dp(4).toFloat())
            }, lp(MATCH, dp(h)))
            val dayName = if (today) L.t("today") else SimpleDateFormat("EEE", if (L.ar) Locale("ar") else Locale.US).format(cal.time)
            col.addView(text(dayName, 10f, if (today) Ui.TEXT else Ui.MUTED, bold = today).apply {
                gravity = Gravity.CENTER
                maxLines = 1
            }, lp(MATCH, WRAP, top = dp(6)))
            bars.addView(col, lp(0, dp(96), 1f, start = if (i > 0) dp(6) else 0))
        }
        week.addView(bars, lp(MATCH, WRAP, top = dp(12)))
        c.addView(week, lp(MATCH, WRAP, top = dp(12)))

        val opts = card(0).apply { setPadding(dp(14), dp(2), dp(14), dp(2)) }
        opts.addView(toggleRow(L.t("auto_saver"), L.t("auto_saver_d"), prefs.autoSaver) {
            prefs.autoSaver = it
            LiveService.start(this, LiveService.ACTION_REFRESH)
        })
        opts.addView(divider())
        opts.addView(toggleRow(L.t("mobile_balanced"), L.t("mobile_balanced_d"), prefs.mobileBalanced) {
            prefs.mobileBalanced = it
            LiveService.start(this, LiveService.ACTION_REFRESH)
        })
        c.addView(opts, lp(MATCH, WRAP, top = dp(12)))
        c.addView(text(L.t("battery_note"), 11f, Ui.MUTED), lp(MATCH, WRAP, top = dp(12)))
    }

    private fun statCard(icon: String, iconColor: Int, title: String, value: String, unit: String, tag: String, tagColor: Int): View {
        val k = card(14)
        k.addView(Icon(this, icon, 20).apply { color = iconColor })
        k.addView(text(title, 12f, Ui.MUTED), lp(MATCH, WRAP, top = dp(6)))
        val v = row()
        v.addView(text(value, 24f, bold = true))
        if (unit.isNotEmpty()) v.addView(text(unit, 12f, Ui.MUTED), lp(WRAP, WRAP, start = dp(4)))
        k.addView(v, lp(MATCH, WRAP, top = dp(2)))
        k.addView(text(tag, 11f, tagColor), lp(MATCH, WRAP, top = dp(2)))
        return k
    }

    /* ======================= Settings ======================= */

    private fun settings(c: LinearLayout) {
        c.addView(text(L.t("settings"), 24f, bold = true))
        updateBanner(c)

        c.addView(label(L.t("language")), lp(MATCH, WRAP, top = dp(18)))
        c.addView(segmented(listOf("ar" to "_العربية", "en" to "_English", "system" to "lang_system"), prefs.lang) {
            prefs.lang = it
            L.load(this)
            LiveService.start(this, LiveService.ACTION_REFRESH)
            recreate()
        }, lp(MATCH, WRAP, top = dp(8)))

        c.addView(label(L.t("theme")), lp(MATCH, WRAP, top = dp(16)))
        c.addView(segmented(listOf("system" to "th_system", "light" to "th_light", "dark" to "th_dark"), prefs.theme) {
            prefs.theme = it
            recreate()
        }, lp(MATCH, WRAP, top = dp(8)))

        c.addView(label(L.t("notch")), lp(MATCH, WRAP, top = dp(16)))
        val n = card(0).apply { setPadding(dp(14), dp(2), dp(14), dp(2)) }
        n.addView(navRow(L.t("position"), "${prefs.scale}%") {
            page = "position"
            draw()
        })
        n.addView(divider())
        n.addView(navRow(L.t("teams"), "${prefs.teams.size}") {
            page = "teams"
            draw()
        })
        n.addView(divider())
        n.addView(toggleRow(L.t("over_full"), null, prefs.overFullscreen) {
            prefs.overFullscreen = it
            LiveService.start(this, LiveService.ACTION_REFRESH)
        })
        n.addView(divider())
        n.addView(toggleRow(L.t("circle"), L.t("circle_d"), prefs.circle) { prefs.circle = it })
        c.addView(n, lp(MATCH, WRAP, top = dp(8)))

        c.addView(label(L.t("alerts")), lp(MATCH, WRAP, top = dp(16)))
        val a = card(0).apply { setPadding(dp(14), dp(2), dp(14), dp(2)) }
        a.addView(toggleRow(L.t("sound"), null, prefs.sound) { prefs.sound = it })
        a.addView(divider())
        a.addView(toggleRow(L.t("vibrate"), null, prefs.vibrate) { prefs.vibrate = it })
        a.addView(divider())
        a.addView(toggleRow(L.t("news"), L.t("news_d"), prefs.news) { prefs.news = it })
        a.addView(divider())
        a.addView(toggleRow(L.t("cards"), L.t("cards_d"), prefs.cards) { prefs.cards = it })
        c.addView(a, lp(MATCH, WRAP, top = dp(8)))

        val ph = row()
        ph.addView(label(L.t("phone")))
        ph.addView(text(L.t("beta"), 10f, Ui.BLUE, bold = true).apply {
            background = Ui.rounded(Ui.BLUE_SOFT, dp(6).toFloat())
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }, lp(WRAP, WRAP, start = dp(8)))
        c.addView(ph, lp(MATCH, WRAP, top = dp(16)))
        val p = card(0).apply { setPadding(dp(14), dp(2), dp(14), dp(12)) }
        p.addView(toggleRow(L.t("phone_on"), null, prefs.phone) {
            prefs.phone = it
            if (it && !listenerGranted()) {
                toast(L.t("phone_perm"))
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            draw()
        })
        if (prefs.phone) {
            p.addView(divider())
            p.addView(navRow(L.t("phone_apps"), L.f("phone_apps_n", prefs.phoneApps.size)) { pickApps() })
            p.addView(divider())
            p.addView(toggleRow(L.t("phone_hide"), null, prefs.phoneHide) { prefs.phoneHide = it })
            p.addView(divider())
            p.addView(toggleRow(L.t("phone_full"), null, prefs.phoneFullOnly) { prefs.phoneFullOnly = it })
        }
        p.addView(text(L.t("phone_d"), 11f, Ui.MUTED), lp(MATCH, WRAP, top = dp(4)))
        c.addView(p, lp(MATCH, WRAP, top = dp(8)))

        c.addView(label(L.t("permissions")), lp(MATCH, WRAP, top = dp(16)))
        val pr = card(0).apply { setPadding(dp(14), dp(2), dp(14), dp(2)) }
        pr.addView(navRow(L.t("setup_again"), "") {
            onboarding = true
            step = 1
            drawOnboarding()
        })
        c.addView(pr, lp(MATCH, WRAP, top = dp(8)))
        c.addView(text(L.t("quick_tile"), 11f, Ui.MUTED), lp(MATCH, WRAP, top = dp(10)))

        val v = row()
        v.addView(text(L.f("version", Updater.current(this)), 12f, Ui.MUTED), lp(0, WRAP, 1f))
        v.addView(link(L.t("check_update")) {
            release = null
            checkUpdate(true)
        })
        c.addView(v, lp(MATCH, WRAP, top = dp(12)))
    }

    private fun navRow(title: String, value: String, onClick: () -> Unit): View {
        val r = row().apply {
            minimumHeight = dp(52)
            isClickable = true
            setOnClickListener { onClick() }
        }
        r.addView(text(title, 14f), lp(0, WRAP, 1f))
        if (value.isNotEmpty()) r.addView(text(value, 12f, Ui.MUTED), lp(WRAP, WRAP, start = dp(8)))
        r.addView(Icon(this, "chevron", 16).apply {
            color = Ui.MUTED
            scaleX = if (L.ar) 1f else -1f
        }, lp(WRAP, WRAP, start = dp(6)))
        return r
    }

    private fun toggleRow(title: String, sub: String?, on: Boolean, save: (Boolean) -> Unit): View {
        val r = row().apply { minimumHeight = dp(54) }
        val t = column()
        t.addView(text(title, 14f))
        if (sub != null) t.addView(text(sub, 11f, Ui.MUTED), lp(WRAP, WRAP, top = dp(1)))
        r.addView(t, lp(0, WRAP, 1f))
        val sw = Switch(this).apply {
            isChecked = on
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.ACCENT, 0xFFD4D4D8.toInt()))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.ACCENT_SOFT, Ui.LINE))
            contentDescription = title
            setOnCheckedChangeListener { _, v -> save(v) }
        }
        r.addView(sw, lp(WRAP, WRAP, start = dp(8)))
        r.setOnClickListener { sw.toggle() }
        return r
    }

    /** A row of options, one selected. Labels starting with "_" are shown as they are. */
    private fun segmented(options: List<Pair<String, String>>, selected: String, pick: (String) -> Unit): View {
        val s = row().apply {
            background = Ui.rounded(Ui.CARD, dp(14).toFloat())
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        options.forEachIndexed { i, (k, key) ->
            val on = k == selected
            s.addView(text(if (key.startsWith("_")) key.drop(1) else L.t(key), 14f, if (on) Ui.ON_ACCENT else Ui.TEXT, bold = on).apply {
                gravity = Gravity.CENTER
                minHeight = dp(40)
                background = if (on) Ui.rounded(Ui.ACCENT, dp(10).toFloat()) else null
                isClickable = true
                setOnClickListener { if (!on) pick(k) }
            }, lp(0, WRAP, 1f, start = if (i > 0) dp(4) else 0))
        }
        return s
    }

    /* ----- sub-page: position and size, with the demo buttons ----- */

    private fun backRow(c: LinearLayout, title: String) {
        val r = row().apply {
            minimumHeight = dp(48)
            isClickable = true
            contentDescription = L.t("back")
            setOnClickListener {
                page = null
                draw()
            }
        }
        r.addView(Icon(this, "back", 22).apply {
            color = Ui.TEXT
            scaleX = if (L.ar) -1f else 1f
        })
        r.addView(text(title, 20f, bold = true), lp(WRAP, WRAP, start = dp(8)))
        c.addView(r)
    }

    private fun positionPage(c: LinearLayout) {
        backRow(c, L.t("position"))
        val k = card()
        k.addView(text(L.t("pos_note"), 13f, Ui.MUTED))
        k.addView(slider(L.t("up_down"), -20, 20, prefs.offsetY) { prefs.offsetY = it }, lp(MATCH, WRAP, top = dp(12)))
        k.addView(slider(L.t("size"), 80, 140, prefs.scale) { prefs.scale = it }, lp(MATCH, WRAP, top = dp(8)))
        c.addView(k, lp(MATCH, WRAP, top = dp(12)))

        c.addView(label(L.t("try")), lp(MATCH, WRAP, top = dp(16)))
        val demos = listOf("goal" to "d_goal", "red" to "d_red", "whistle" to "d_whistle", "lineups" to "d_lineups", "against" to "d_against", "news" to "d_news")
        for (rowItems in demos.chunked(3)) {
            val r = row()
            rowItems.forEachIndexed { i, (kind, key) ->
                r.addView(button(L.t(key), primary = kind == "goal") { LiveService.start(this, LiveService.ACTION_DEMO, kind) }, lp(0, WRAP, 1f, start = if (i > 0) dp(8) else 0))
            }
            c.addView(r, lp(MATCH, WRAP, top = dp(8)))
        }
        c.addView(text(L.t("try_note"), 12f, Ui.MUTED), lp(MATCH, WRAP, top = dp(10)))
    }

    private fun slider(title: String, lo: Int, hi: Int, value: Int, save: (Int) -> Unit): View {
        val box = column()
        val t = text("$title: $value", 13f, Ui.MUTED)
        box.addView(t)
        box.addView(SeekBar(this).apply {
            max = hi - lo
            progress = value - lo
            progressTintList = ColorStateList.valueOf(Ui.ACCENT)
            thumbTintList = ColorStateList.valueOf(Ui.ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    t.text = "$title: ${p + lo}"
                }

                override fun onStartTrackingTouch(s: SeekBar) {}

                override fun onStopTrackingTouch(s: SeekBar) {
                    save(s.progress + lo)
                    LiveService.start(this@MainActivity, LiveService.ACTION_REFRESH)
                    LiveService.start(this@MainActivity, LiveService.ACTION_DEMO, "none")
                }
            })
        }, lp(MATCH, dp(44), top = dp(4)))
        return box
    }

    /* ----- sub-page (and onboarding step 1): favourite teams ----- */

    private fun teamsPage(c: LinearLayout) {
        backRow(c, L.t("teams"))
        teamsEditor(c)
    }

    private fun teamsEditor(c: LinearLayout) {
        val list = column()
        val results = column()
        fun drawTeams() {
            list.removeAllViews()
            for (t in prefs.teams) {
                val r = row().apply { minimumHeight = dp(52) }
                r.addView(crest(t.logo, 28))
                r.addView(text(t.name, 14f, bold = true), lp(0, WRAP, 1f, start = dp(10)))
                r.addView(button(L.t("remove")) {
                    prefs.teams = prefs.teams.filter { it.id != t.id }
                    drawTeams()
                    LiveService.start(this, LiveService.ACTION_REFRESH)
                })
                list.addView(r)
            }
            if (prefs.teams.isEmpty()) list.addView(text(L.t("no_teams"), 13f, Ui.MUTED))
        }
        val k = card()
        k.addView(list)
        val search = row().apply {
            background = Ui.rounded(Ui.CARD2, dp(12).toFloat())
            setPadding(dp(12), 0, dp(6), 0)
        }
        val box = EditText(this).apply {
            hint = L.t("add_team")
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 14f
            background = null
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val go = {
            val q = box.text.toString().trim()
            if (q.length >= 2) {
                results.removeAllViews()
                results.addView(text(L.t("searching"), 12f, Ui.MUTED))
                io.execute {
                    val found = try {
                        Api.search(q)
                    } catch (e: Exception) {
                        null
                    }
                    main.post {
                        results.removeAllViews()
                        when {
                            found == null -> results.addView(text(L.t("no_net"), 12f, Ui.RED))
                            found.isEmpty() -> results.addView(text(L.t("no_results"), 12f, Ui.MUTED))
                        }
                        for (t in found ?: emptyList()) {
                            val r = row().apply { minimumHeight = dp(52) }
                            r.addView(crest(t.logo, 24))
                            r.addView(text(t.name, 13f), lp(0, WRAP, 1f, start = dp(10)))
                            val have = prefs.teams.any { it.id == t.id }
                            r.addView(button(L.t(if (have) "added" else "add"), primary = !have) {
                                if (prefs.teams.none { it.id == t.id }) {
                                    prefs.teams = prefs.teams + t
                                    drawTeams()
                                    LiveService.start(this, LiveService.ACTION_REFRESH)
                                }
                            })
                            results.addView(r)
                        }
                    }
                }
            }
        }
        box.setOnEditorActionListener { _, _, _ -> go(); true }
        search.addView(box, lp(0, WRAP, 1f))
        search.addView(Icon(this, "search", 20).apply {
            color = Ui.ACCENT
            isClickable = true
            contentDescription = L.t("add_team")
            setOnClickListener { go() }
        }, lp(dp(44), dp(44)))
        k.addView(search, lp(MATCH, WRAP, top = dp(10)))
        k.addView(results, lp(MATCH, WRAP, top = dp(6)))
        c.addView(k, lp(MATCH, WRAP, top = dp(12)))
        drawTeams()
    }

    /* ----- phone notifications: pick apps ----- */

    private fun listenerGranted(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    private fun pickApps() {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        val chosen = prefs.phoneApps.toMutableSet()
        val checked = BooleanArray(apps.size) { apps[it].first in chosen }
        AlertDialog.Builder(this)
            .setTitle(L.t("pick_apps"))
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, on ->
                if (on) chosen += apps[i].first else chosen -= apps[i].first
            }
            .setPositiveButton(L.t("done")) { _, _ ->
                prefs.phoneApps = chosen
                draw()
            }
            .show()
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

    /* ======================= Update banner ======================= */

    private var bar: ProgressBar? = null
    private var note: TextView? = null

    private fun checkUpdate(tell: Boolean = false) {
        if (downloading) return
        io.execute {
            val r = try {
                Updater.latest()
            } catch (e: Exception) {
                null
            }
            main.post {
                release = r?.takeIf { Updater.newer(it.version, Updater.current(this)) }
                if (tell && release == null) toast(L.t("up_to_date"))
                if (!onboarding && release != null) draw()
            }
        }
    }

    private fun updateBanner(c: LinearLayout) {
        val r = release ?: return
        val k = card().apply { background = Ui.rounded(Ui.ACCENT_SOFT, dp(20).toFloat()) }
        k.addView(text(L.f("new_version", r.version), 15f, Ui.ACCENT, bold = true))
        k.addView(text(L.t("update_note"), 12f, Ui.MUTED), lp(MATCH, WRAP, top = dp(2)))
        if (!downloading) k.addView(button(L.t("download"), primary = true) { download(r) }, lp(MATCH, WRAP, top = dp(10)))
        val b = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(Ui.ACCENT)
            visibility = if (downloading || dlPct >= 0) View.VISIBLE else View.GONE
            isIndeterminate = downloading && dlPct < 0
            progress = dlPct.coerceAtLeast(0)
        }
        k.addView(b, lp(MATCH, WRAP, top = dp(8)))
        val t = text(dlNote, 12f, Ui.MUTED)
        k.addView(t, lp(MATCH, WRAP, top = dp(4)))
        bar = b
        note = t
        c.addView(k, lp(MATCH, WRAP, top = dp(16)))
    }

    private fun download(r: Updater.Release) {
        if (downloading) return
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            dlNote = L.t("allow_install")
            note?.text = dlNote
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        downloading = true
        dlPct = -1
        dlNote = L.t("dl_start")
        InstallReceiver.lastError = null
        draw()
        io.execute {
            try {
                Installer.downloadAndInstall(this, r) { done, total ->
                    main.post {
                        val mb = { b: Long -> String.format(Locale.US, "%.1f", b / 1_048_576.0) }
                        if (total > 0) {
                            dlPct = (done * 100 / total).toInt()
                            dlNote = L.f("dl_progress", dlPct, mb(done), mb(total))
                            bar?.isIndeterminate = false
                            bar?.progress = dlPct
                        } else {
                            dlNote = L.f("dl_bytes", mb(done))
                        }
                        note?.text = dlNote
                    }
                }
                main.post {
                    downloading = false
                    dlPct = 100
                    dlNote = L.t("dl_done")
                    draw()
                    main.postDelayed({
                        InstallReceiver.lastError?.let {
                            dlNote = L.f("install_failed", it)
                            note?.text = dlNote
                        }
                    }, 4000)
                }
            } catch (e: Exception) {
                main.post {
                    downloading = false
                    dlPct = -1
                    dlNote = L.f("dl_failed", e.message ?: "—")
                    draw()
                }
            }
        }
    }

    /* ======================= First run: permissions step by step ======================= */

    private val steps = 5

    private fun granted(s: Int): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return when (s) {
            1 -> Settings.canDrawOverlays(this)
            2 -> TapService.instance != null
            3 -> pm.isIgnoringBatteryOptimizations(packageName)
            4 -> Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            else -> true
        }
    }

    private fun finishOnboarding() {
        prefs.onboarded = true
        onboarding = false
        LiveService.start(this)
        draw()
    }

    private fun drawOnboarding() {
        if (step >= steps) {
            finishOnboarding()
            return
        }
        root.removeAllViews()
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val c = column().apply { setPadding(dp(24), dp(28), dp(24), dp(24)) }
        scroll.addView(c)
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))

        val head = row()
        head.addView(text(L.f("step_of", step + 1, steps), 13f, Ui.MUTED), lp(0, WRAP, 1f))
        head.addView(link(L.t("skip")) { finishOnboarding() }.apply { setTextColor(Ui.MUTED) })
        c.addView(head)
        val dots = row()
        for (i in 0 until steps) {
            dots.addView(View(this).apply { background = Ui.rounded(if (i <= step) Ui.ACCENT else Ui.LINE, dp(2).toFloat()) }, lp(0, dp(4), 1f, start = if (i > 0) dp(6) else 0))
        }
        c.addView(dots, lp(MATCH, WRAP, top = dp(8)))

        val (title, body) = when (step) {
            0 -> "ob1_t" to "ob1_d"
            1 -> "ob2_t" to "ob2_d"
            2 -> "ob3_t" to "ob3_d"
            3 -> "ob4_t" to "ob4_d"
            else -> "ob5_t" to "ob5_d"
        }
        if (step in 1..2) c.addView(phoneArt(), lp(dp(200), dp(170), top = dp(22)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        c.addView(text(L.t(title), 24f, bold = true).apply { setLineSpacing(0f, 1.2f) }, lp(MATCH, WRAP, top = dp(22)))
        c.addView(text(L.t(body), 15f, Ui.MUTED).apply { setLineSpacing(0f, 1.3f) }, lp(MATCH, WRAP, top = dp(10)))

        var action: (() -> Unit)? = null
        when (step) {
            0 -> {
                c.addView(label(L.t("language")), lp(MATCH, WRAP, top = dp(20)))
                c.addView(segmented(listOf("ar" to "_العربية", "en" to "_English", "system" to "lang_system"), prefs.lang) {
                    prefs.lang = it
                    L.load(this)
                    recreate()
                }, lp(MATCH, WRAP, top = dp(8)))
                c.addView(label(L.t("ob_teams")), lp(MATCH, WRAP, top = dp(16)))
                teamsEditor(c)
            }
            1 -> action = { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            2 -> {
                c.addView(hint(L.t("ob3_h")), lp(MATCH, WRAP, top = dp(14)))
                action = { openAccessibility() }
            }
            3 -> {
                val k = card(0).apply { setPadding(dp(14), dp(2), dp(14), dp(2)) }
                k.addView(navRow(L.t("battery_step"), if (granted(3)) L.t("granted") else "") {
                    try {
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                    } catch (e: Exception) {
                        appDetails()
                    }
                })
                k.addView(divider())
                k.addView(navRow(L.t("autostart_step"), "") {
                    try {
                        startActivity(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
                    } catch (e: Exception) {
                        appDetails()
                    }
                })
                k.addView(divider())
                k.addView(navRow(L.t("popups_step"), "") {
                    try {
                        startActivity(Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", packageName))
                    } catch (e: Exception) {
                        appDetails()
                    }
                })
                c.addView(k, lp(MATCH, WRAP, top = dp(16)))
            }
            else -> action = {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        if (step == 1 || step == 2) c.addView(hint(L.t("ob_hint")), lp(MATCH, WRAP, top = dp(14)))

        c.addView(View(this), lp(MATCH, 0, 1f))
        val done = granted(step)
        val last = step == steps - 1
        val act = action
        if (act != null && !done) {
            c.addView(button(L.t("open_setting"), primary = true) { act() }, lp(MATCH, dp(54), top = dp(20)))
            c.addView(button(L.t("next_step")) {
                step++
                drawOnboarding()
            }, lp(MATCH, dp(48), top = dp(8)))
        } else {
            c.addView(button(L.t(if (last) "start" else "next_step"), primary = true) {
                step++
                drawOnboarding()
            }, lp(MATCH, dp(54), top = dp(20)))
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (onboarding && step == 4) {
            step++
            drawOnboarding()
        }
    }

    private fun hint(s: String): View {
        val r = row().apply {
            gravity = Gravity.TOP
            background = Ui.rounded(Ui.CARD, dp(16).toFloat())
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        r.addView(Icon(this, "info", 20).apply { color = Ui.BLUE })
        r.addView(text(s, 13f, Ui.MUTED).apply { setLineSpacing(0f, 1.25f) }, lp(0, WRAP, 1f, start = dp(10)))
        return r
    }

    /** The phone-with-notch picture in the first steps. */
    private fun phoneArt(): View {
        val f = FrameLayout(this).apply { background = Ui.rounded(Ui.CARD, dp(28).toFloat(), Ui.LINE, dp(1)) }
        val n = row().apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = Ui.rounded(0xFF000000.toInt(), dp(999).toFloat(), 0xFF34D399.toInt(), dp(1))
            setPadding(dp(9), dp(5), dp(9), dp(5))
        }
        n.addView(text("23'", 10f, 0xFF34D399.toInt(), bold = true))
        n.addView(View(this).apply { background = Ui.rounded(0xFFB91C1C.toInt(), dp(7).toFloat()) }, lp(dp(14), dp(14), start = dp(6)))
        n.addView(text("1", 12f, 0xFFFFFFFF.toInt(), bold = true), lp(WRAP, WRAP, start = dp(6)))
        n.addView(View(this).apply { background = Ui.rounded(0xFF1F2937.toInt(), dp(6).toFloat()) }, lp(dp(11), dp(11), start = dp(6)))
        n.addView(text("0", 12f, 0xFFFFFFFF.toInt(), bold = true), lp(WRAP, WRAP, start = dp(6)))
        n.addView(View(this).apply { background = Ui.rounded(0xFFF8FAFC.toInt(), dp(7).toFloat()) }, lp(dp(14), dp(14), start = dp(6)))
        n.addView(View(this).apply { background = Ui.rounded(0xFF34D399.toInt(), dp(3).toFloat()) }, lp(dp(5), dp(5), start = dp(6)))
        f.addView(n, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(14) })
        val lines = column()
        for (w in listOf(1f, 0.7f, 0f, 0.85f)) {
            if (w == 0f) {
                lines.addView(View(this).apply { background = Ui.rounded(Ui.CARD2, dp(12).toFloat()) }, lp(MATCH, dp(48), top = dp(8)))
            } else {
                lines.addView(View(this).apply { background = Ui.rounded(Ui.LINE, dp(5).toFloat()) }, LinearLayout.LayoutParams((dp(150) * w).toInt(), dp(10)).apply { topMargin = dp(8) })
            }
        }
        f.addView(lines, FrameLayout.LayoutParams(dp(150), WRAP, Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(56) })
        return f
    }

    private fun appDetails() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    /** Notch Live's own accessibility page where the ROM allows it, else the list with it highlighted. */
    private fun openAccessibility() {
        val component = ComponentName(this, TapService::class.java).flattenToString()
        try {
            startActivity(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, component))
            return
        } catch (e: Exception) {
            // Locked to system apps on most ROMs.
        }
        val args = Bundle().apply { putString(":settings:fragment_args_key", component) }
        startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:fragment_args_key", component)
                .putExtra(":settings:show_fragment_args", args),
        )
    }
}
