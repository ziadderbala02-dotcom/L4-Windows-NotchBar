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
    private var filter = "today"
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
            // Not while the teams are being rearranged (a redraw would drop the card under the finger).
            if (!onboarding && page == null && tab == 0 && !editTeams) drawHome()
            // The matches list stays fresh while it's open: every 30 s.
            if (!onboarding && page == null && tab == 1 && !loadingList && System.currentTimeMillis() - loadedAt > 30_000L) load(quiet = true)
            main.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        L.load(this)
        Ui.load(this)
        tab = savedInstanceState?.getInt("tab") ?: intent.getIntExtra("tab", 0)
        step = savedInstanceState?.getInt("step") ?: 0
        root = FrameLayout(this).apply {
            setBackgroundColor(Ui.BG)
            layoutDirection = if (L.ar) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        setContentView(root)
        // After setContentView: before it, some ROMs (HyperOS) have no window decor yet and crash.
        Ui.bars(this)
        // Android 15 draws the app under the status bar, the navigation bar and the keyboard:
        // keep the content clear of all three.
        root.setOnApplyWindowInsetsListener { v, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime() or android.view.WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                android.view.WindowInsets.CONSUMED
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
                @Suppress("DEPRECATION")
                ins.consumeSystemWindowInsets()
            }
        }
        onboarding = !prefs.onboarded || savedInstanceState?.getBoolean("onboarding") == true
        // From the notch: an empty or long-pressed shortcut slot opens its picker.
        intent.getIntExtra("slot", -1).takeIf { it >= 0 && savedInstanceState == null }?.let {
            pickIndex = it
            backTo = null
            page = "slot_pick"
        }
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
            if (stepId() in setOf("overlay", "access", "notif", "restricted") && granted(step)) step++
            drawOnboarding()
        } else {
            askCallPerm(false)
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
            page != null -> goBack()
            !onboarding && tab != 0 -> {
                tab = 0
                draw()
            }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    /* ======================= frame: content + bottom bar ======================= */

    private fun draw() {
        if (onboarding && page == null) {
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
            "comps" -> compsPage(content)
            "sounds" -> soundsPage(content)
            "device" -> devicePage(content)
            "slots" -> slotsPage(content)
            "idle_content" -> idleContentPage(content)
            "city" -> cityPage(content)
            "slot_pick" -> slotPickPage(content)
            "search_team" -> searchPage(content, comps = false)
            "search_comp" -> searchPage(content, comps = true)
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
                    if (i == 1 && tab != 1) {
                        // Opening Matches: back to "My teams".
                        scope = "mine"
                        showAllOnce = false
                    }
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

    private fun dayHome(box: LinearLayout) {
        val show = prefs.idleShow
        val now = System.currentTimeMillis()
        val loc = if (L.ar) Locale("ar", "EG") else Locale.ENGLISH
        // Today.
        if ("date" in show || "hijri" in show) {
            val k = card()
            k.addView(text(L.t("today"), 13f, Ui.MUTED))
            if ("date" in show) k.addView(text(SimpleDateFormat("EEEE d MMMM", loc).format(java.util.Date(now)), 20f, bold = true), lp(MATCH, WRAP, top = dp(6)))
            if ("hijri" in show) Ambient.hijriText(now).takeIf { it.isNotBlank() }?.let {
                k.addView(text(it, 14f, Ui.MUTED), lp(MATCH, WRAP, top = dp(2)))
            }
            box.addView(k, lp(MATCH, WRAP, top = dp(18)))
        }
        // Weather.
        if ("weather" in show) {
            val k = card()
            val head = row()
            head.addView(text(L.t("ic_weather"), 13f, Ui.MUTED), lp(0, WRAP, 1f))
            head.addView(text(Ambient.place.ifEmpty { Locator.city(this, prefs).name }, 12f, Ui.MUTED))
            k.addView(head)
            val t = Ambient.temp
            if (t == null) {
                k.addView(text(L.t("loading"), 14f, Ui.MUTED), lp(MATCH, WRAP, top = dp(8)))
                io.execute {
                    try {
                        Ambient.refreshWeather(Locator.city(this, prefs))
                        main.post { drawHome() }
                    } catch (e: Exception) {
                        // Offline.
                    }
                }
            } else {
                val r = row()
                r.addView(text("$t°", 34f, bold = true))
                val side = column()
                side.addView(text(Ambient.word(), 15f, bold = true))
                val parts = listOfNotNull(
                    Ambient.feels?.let { L.f("feels", it) },
                    if (Ambient.todayMax != null && Ambient.todayMin != null) L.f("al_detail", Ambient.todayMax!!, Ambient.todayMin!!) else null,
                )
                side.addView(text(parts.joinToString(" · "), 12f, Ui.MUTED), lp(WRAP, WRAP, top = dp(2)))
                r.addView(side, lp(0, WRAP, 1f, start = dp(14)))
                k.addView(r, lp(MATCH, WRAP, top = dp(8)))
                Ambient.alert?.takeIf { prefs.weatherAlerts && it.day == SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(now)) }?.let { a ->
                    val (title, _) = Ambient.alertText(a, now)
                    k.addView(text("⚠ $title", 13f, Ui.AMBER, bold = true), lp(MATCH, WRAP, top = dp(10)))
                }
            }
            box.addView(k, lp(MATCH, WRAP, top = dp(12)))
        }
        // Prayer times, the next one marked.
        if ("prayer" in show) {
            val k = card()
            k.addView(text(L.t("prayer_times"), 13f, Ui.MUTED))
            val day = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val list = try {
                Ambient.prayers(Locator.city(this, prefs), day)
            } catch (e: Exception) {
                emptyList()
            }
            val next = list.firstOrNull { it.second > now }
            val fmt = SimpleDateFormat("h:mm a", loc)
            for ((name, at) in list) {
                val isNext = next?.first == name
                val r = row().apply { minimumHeight = dp(34) }
                r.addView(text(L.t("pr_$name"), 15f, if (isNext) Ui.ACCENT else Ui.TEXT, bold = isNext), lp(0, WRAP, 1f))
                if (isNext) {
                    val mins = ((at - now + 59_999L) / 60_000L).toInt()
                    r.addView(text(remaining(0, mins / 60, mins % 60), 12f, Ui.ACCENT))
                }
                r.addView(text(fmt.format(java.util.Date(at)), 15f, if (at < now) Ui.MUTED else Ui.TEXT, bold = isNext), lp(WRAP, WRAP, start = dp(10)))
                k.addView(r, lp(MATCH, WRAP, top = dp(4)))
            }
            box.addView(k, lp(MATCH, WRAP, top = dp(12)))
        }
        // The notch: on/off, what it shows, shortcuts.
        val n = card(0).apply { setPadding(dp(14), dp(2), dp(14), dp(2)) }
        n.addView(toggleRow(L.t("idle"), L.t("idle_d"), prefs.idleNotch) {
            prefs.idleNotch = it
            LiveService.start(this, LiveService.ACTION_REFRESH)
            drawHome()
        })
        n.addView(divider())
        n.addView(navRow(L.t("idle_content"), "") {
            page = "idle_content"
            draw()
        })
        n.addView(divider())
        n.addView(navRow(L.t("slots"), L.f("slots_n", Shortcuts.load(this).count { it != null })) {
            page = "slots"
            draw()
        })
        box.addView(n, lp(MATCH, WRAP, top = dp(12)))
    }

    private fun drawHome() {
        val box = homeBox ?: return
        if (box.parent == null) return
        box.removeAllViews()
        val (now, known) = LiveService.instance?.snapshot() ?: (null to emptyList())

        // Taps on the notch need the accessibility service; phones sometimes turn it off
        // (switching to Xiaomi's Second Space, a battery cleaner…).
        if (TapService.instance == null) {
            val w = card(14).apply { background = Ui.rounded(Ui.AMBER_SOFT, dp(18).toFloat(), Ui.AMBER, dp(1)) }
            w.addView(text(L.t("access_off"), 14f, Ui.AMBER, bold = true))
            w.addView(text(L.t("access_off_d"), 12f, Ui.TEXT), lp(MATCH, WRAP, top = dp(4)))
            if (restrictedLikely()) w.addView(restrictedGuide(), lp(MATCH, WRAP, top = dp(10)))
            else w.addView(button(L.t("access_fix"), primary = true) { openAccessibility() }, lp(MATCH, WRAP, top = dp(10)))
            box.addView(w, lp(MATCH, WRAP))
            box.addView(View(this), lp(MATCH, dp(12)))
        }

        // 1.9.2: notification access off: no calls, music or island circle. Say so with the way back.
        if (prefs.liveActs && !SystemPill.listenerOn(this)) {
            val w = card(14).apply { background = Ui.rounded(Ui.AMBER_SOFT, dp(18).toFloat(), Ui.AMBER, dp(1)) }
            w.addView(text(L.t("notif_off"), 14f, Ui.AMBER, bold = true))
            w.addView(text(L.t("notif_off_d"), 12f, Ui.TEXT), lp(MATCH, WRAP, top = dp(4)))
            w.addView(button(L.t("open_setting"), primary = true) {
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (e: Exception) {
                    appDetails()
                }
            }, lp(MATCH, WRAP, top = dp(10)))
            box.addView(w, lp(MATCH, WRAP))
            box.addView(View(this), lp(MATCH, dp(12)))
        }

        // 1.9.1: not into football: Home is about the day instead (each card only if the
        // always-on notch shows that thing, so no prayers for someone who turned them off).
        if (!prefs.football) {
            dayHome(box)
            return
        }

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
            nc.isClickable = true
            nc.setOnClickListener { startActivity(MatchActivity.intent(this, next)) }
            val r = row()
            r.addView(teamBadge(next.home), lp(0, WRAP, 1f))
            val mid = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
            val left = (next.kickoff - nowMs) / 1000
            val d = left / 86400
            val hm = String.format(Locale.US, "%02d:%02d", (left % 86400) / 3600, (left % 3600) / 60)
            val hours = ((left % 86400) / 3600).toInt()
            val mins = ((left % 3600) / 60).toInt()
            mid.addView(text(L.t("remaining"), 11f, Ui.MUTED).apply { gravity = Gravity.CENTER })
            mid.addView(text(remaining(d.toInt(), hours, mins), 16f, bold = true).apply { gravity = Gravity.CENTER }, lp(WRAP, WRAP, top = dp(2)))
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
        // All of them, four a row. Long-press one to drag it (the order is the priority); × removes.
        val grid = DragGrid(this, 4, dp(10), swap = false,
            onMove = { f, t ->
                val l = prefs.teams.toMutableList()
                l.add(t, l.removeAt(f))
                prefs.teams = l
                LiveService.start(this, LiveService.ACTION_REFRESH)
                drawHome()
            },
            onEditing = {
                editTeams = it
                drawHomeHint()
            })
        for (t in prefs.teams) {
            val cell = FrameLayout(this)
            val tc = card(12).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                setOnClickListener {
                    if (editTeams) {
                        editTeams = false
                        grid.editing = false
                        drawHomeHint()
                    }
                }
            }
            tc.addView(crest(t.logo, 34))
            tc.addView(text(t.name, 13f, bold = true).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, lp(MATCH, WRAP, top = dp(6)))
            val playing = known.any { it.state == Api.LIVE && (it.home.id == t.id || it.away.id == t.id) }
            tc.addView(text(if (playing) L.t("live") else " ", 11f, Ui.ACCENT).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))
            cell.addView(tc, FrameLayout.LayoutParams(MATCH, MATCH))
            val x = text("×", 16f, 0xFFFFFFFF.toInt(), bold = true).apply {
                tag = "x"
                gravity = Gravity.CENTER
                background = Ui.rounded(0xFFEF4444.toInt(), dp(12).toFloat())
                contentDescription = L.t("remove")
                isClickable = true
                setOnClickListener {
                    prefs.teams = prefs.teams.filter { it.id != t.id }
                    LiveService.start(this@MainActivity, LiveService.ACTION_REFRESH)
                    drawHome()
                }
            }
            cell.addView(x, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END))
            // Pass the long press from the card to the cell, so the grid can lift it.
            tc.setOnLongClickListener { cell.performLongClick() }
            grid.addCell(cell)
        }
        grid.editing = editTeams
        box.addView(grid, lp(MATCH, WRAP, top = dp(8)))
        if (prefs.teams.isEmpty()) box.addView(text(L.t("no_teams"), 13f, Ui.MUTED), lp(MATCH, WRAP, top = dp(6)))
        else box.addView(text(L.t(if (editTeams) "remove_done" else "remove_hint"), 11f, Ui.MUTED).also { teamsHint = it }, lp(MATCH, WRAP, top = dp(8)))

        // Latest news: everything the notch showed or skipped.
        val news = prefs.newsLog.take(8)
        if (prefs.news && news.isNotEmpty()) {
            box.addView(text(L.t("latest_news"), 16f, bold = true), lp(MATCH, WRAP, top = dp(18)))
            val k = card(0).apply { setPadding(dp(14), dp(4), dp(14), dp(4)) }
            news.forEachIndexed { i, n ->
                if (i > 0) k.addView(divider())
                val r = row().apply {
                    minimumHeight = dp(56)
                    setPadding(0, dp(8), 0, dp(8))
                    isClickable = true
                    setOnClickListener {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.url)))
                        } catch (e: Exception) {
                            // No browser.
                        }
                    }
                }
                r.addView(crest(n.logo, 24))
                val t = column()
                t.addView(text(n.title, 13f).apply { maxLines = 2 })
                t.addView(text("${n.team} · ${android.text.format.DateUtils.getRelativeTimeSpanString(n.at)}", 11f, Ui.MUTED), lp(WRAP, WRAP, top = dp(2)))
                r.addView(t, lp(0, WRAP, 1f, start = dp(10)))
                k.addView(r)
            }
            box.addView(k, lp(MATCH, WRAP, top = dp(8)))
        }
    }

    /** Home: × shown on the team cards (after a long press). */
    private var editTeams = false
    private var teamsHint: TextView? = null

    private fun drawHomeHint() {
        teamsHint?.text = L.t(if (editTeams) "remove_done" else "remove_hint")
    }

    /** "9 ساعات و36 دقيقة", "يومان و3 ساعات" (Arabic counts follow the number). */
    private fun remaining(days: Int, hours: Int, mins: Int): String {
        fun unit(n: Int, one: String, two: String, few: String, many: String): String = when {
            !L.ar -> "$n $many".let { if (n == 1) "1 $one" else it }
            n == 1 -> one
            n == 2 -> two
            n in 3..10 -> "$n $few"
            else -> "$n $many"
        }
        val d = if (L.ar) unit(days, "يوم", "يومان", "أيام", "يومًا") else unit(days, "day", "", "", "days")
        val h = if (L.ar) unit(hours, "ساعة", "ساعتان", "ساعات", "ساعة") else unit(hours, "hour", "", "", "hours")
        val m = if (L.ar) unit(mins, "دقيقة", "دقيقتان", "دقائق", "دقيقة") else unit(mins, "minute", "", "", "minutes")
        val and = if (L.ar) " و" else " "
        return when {
            days > 0 -> if (hours > 0) d + and + h else d
            hours > 0 -> if (mins > 0) h + and + m else h
            else -> m
        }
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
        if (!prefs.football) {
            // 1.9: football is off: say so, with a way to turn it on (teams, competitions, size).
            c.addView(text(L.t("tab_matches"), 24f, bold = true))
            val k = card(18)
            k.addView(Icon(this, "ball", 28).apply { color = Ui.MUTED })
            k.addView(text(L.t("fb_off_t"), 17f, bold = true), lp(MATCH, WRAP, top = dp(10)))
            k.addView(text(L.t("fb_off_d"), 13f, Ui.MUTED).apply { setLineSpacing(0f, 1.25f) }, lp(MATCH, WRAP, top = dp(6)))
            k.addView(button(L.t("fb_on"), primary = true) { startFootballWizard() }, lp(MATCH, dp(52), top = dp(14)))
            c.addView(k, lp(MATCH, WRAP, top = dp(16)))
            return
        }
        val head = row()
        head.addView(text(L.t("tab_matches"), 24f, bold = true), lp(0, WRAP, 1f))
        head.addView(link(L.t("refresh")) { load() })
        c.addView(head)
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
        listOf("today" to "f_today", "live" to "f_live", "mine" to "f_mine", "comps" to "f_comps", "pinned" to "f_pinned").forEachIndexed { i, (k, key) ->
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
        if (filter == "live" || filter == "today") {
            // Which matches: your teams (and pinned), your competitions, or all of them.
            c.addView(segmented(listOf("mine" to "scope_mine", "comps" to "scope_comps", "all" to "scope_all"), scope) {
                scope = it
                draw()
            }, lp(MATCH, WRAP, top = dp(10)))
        }
        if (filter == "comps") compPicker(c)
        c.addView(text(L.t("pin_note"), 12f, Ui.MUTED), lp(MATCH, WRAP, top = dp(10)))
        listBox = column()
        c.addView(listBox, lp(MATCH, WRAP, top = dp(4)))
        if (loaded == null) load() else drawList()
    }

    /** The competition being shown in the Competitions chip (the first followed one by default). */
    private fun pickedComp(): TeamOption? {
        val comps = prefs.comps
        return comps.firstOrNull { it.id == prefs.compPick } ?: comps.firstOrNull()
    }

    /** One button with the competition's name; it opens a sheet to choose, search or manage. */
    private fun compPicker(c: LinearLayout) {
        val comp = pickedComp()
        val b = row().apply {
            background = Ui.rounded(Ui.CARD, dp(14).toFloat(), Ui.LINE, dp(1))
            setPadding(dp(12), 0, dp(12), 0)
            minimumHeight = dp(52)
            isClickable = true
            setOnClickListener { compSheet() }
        }
        if (comp != null) {
            b.addView(crest(comp.logo, 26))
            b.addView(text(comp.name, 15f, bold = true).apply { maxLines = 1 }, lp(0, WRAP, 1f, start = dp(10)))
        } else {
            b.addView(text(L.t("pick_comp"), 15f, Ui.MUTED), lp(0, WRAP, 1f))
        }
        b.addView(text("▾", 16f, Ui.MUTED))
        c.addView(b, lp(MATCH, WRAP, top = dp(10)))
    }

    /** The sheet: search your competitions, pick one, or add / reorder / remove (manage). */
    private fun compSheet() {
        val d = android.app.Dialog(this)
        val box = column(16).apply {
            background = Ui.rounded(Ui.BG, dp(22).toFloat())
            layoutDirection = if (L.ar) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        box.addView(text(L.t("comps"), 18f, bold = true))
        val field = EditText(this).apply {
            hint = L.t("search_comp_hint")
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 14f
            background = Ui.rounded(Ui.CARD, dp(12).toFloat())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        box.addView(field, lp(MATCH, WRAP, top = dp(10)))
        val list = column()
        val scroll = ScrollView(this).apply { addView(list) }
        box.addView(scroll, lp(MATCH, dp(300), top = dp(8)))
        fun fill() {
            list.removeAllViews()
            val q = field.text.toString().trim()
            val comps = prefs.comps.filter { q.isEmpty() || it.name.contains(q, true) }
            for (t in comps) {
                val r = row().apply {
                    minimumHeight = dp(50)
                    isClickable = true
                    setOnClickListener {
                        prefs.compPick = t.id
                        d.dismiss()
                        loaded = null
                        draw()
                    }
                }
                r.addView(crest(t.logo, 26))
                r.addView(text(t.name, 14f, if (t.id == pickedComp()?.id) Ui.ACCENT else Ui.TEXT, bold = true), lp(0, WRAP, 1f, start = dp(10)))
                list.addView(r)
            }
            if (comps.isEmpty()) list.addView(text(L.t("no_comps"), 13f, Ui.MUTED), lp(MATCH, WRAP, top = dp(8)))
        }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = fill()
        })
        fill()
        val actions = row()
        actions.addView(button(L.t("manage_comps")) {
            d.dismiss()
            page = "comps"
            draw()
        }, lp(0, WRAP, 1f))
        actions.addView(button("+  " + L.t("add_comp"), primary = true) {
            d.dismiss()
            backTo = null
            searchQuery = ""
            page = "search_comp"
            draw()
        }, lp(0, WRAP, 1f, start = dp(8)))
        box.addView(actions, lp(MATCH, WRAP, top = dp(10)))
        d.setContentView(box)
        d.window?.let { w ->
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            w.setGravity(Gravity.BOTTOM)
            w.setLayout(MATCH, WRAP)
        }
        d.show()
    }

    /** Live / Today: "mine" each time the screen opens (the choice isn't remembered). */
    private var scope = "mine"
    private var showAllOnce = false

    private var loadingList = false
    private var loadedAt = 0L

    /** [quiet]: the 30 s refresh, which keeps the list on screen while it loads. */
    private fun load(quiet: Boolean = false) {
        if (!quiet) {
            loadError = false
            listBox?.removeAllViews()
            listBox?.addView(text(L.t("loading"), 13f, Ui.MUTED), lp(MATCH, WRAP, top = dp(12)))
        }
        loadingList = true
        val f = filter
        val comp = pickedComp()?.id
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
                    "comps" -> if (comp == null) emptyList() else Api.compGames(comp)
                    else -> Api.liveNow()
                }
            } catch (e: Exception) {
                null
            }
            main.post {
                loadingList = false
                loadedAt = System.currentTimeMillis()
                if (f != filter) return@post
                if (quiet && r == null && loaded != null) return@post
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
        val comps = prefs.comps.map { it.id }.toSet()
        val sc = if ((filter == "live" || filter == "today") && q.isEmpty() && !showAllOnce) scope else "all"
        val list = all
            .filter {
                when (sc) {
                    "mine" -> it.home.id in favs || it.away.id in favs || it.id in pins
                    "comps" -> it.compId in comps || it.id in pins
                    else -> true
                }
            }
            .filter { q.isEmpty() || it.home.name.contains(q, true) || it.away.name.contains(q, true) || it.comp.contains(q, true) }
        if (filter == "comps") {
            // A league's last round and the next ones, round by round.
            val rounds = list.groupBy { it.round }.toSortedMap()
            if (list.isEmpty()) box.addView(text(L.t(if (prefs.comps.isEmpty()) "no_comps" else "no_matches"), 13f, Ui.MUTED), lp(MATCH, WRAP, top = dp(12)))
            for ((round, games) in rounds) {
                if (round > 0) box.addView(text(L.f("round_n", round), 12f, Ui.ACCENT, bold = true), lp(MATCH, WRAP, top = dp(14)))
                for (m in games.sortedBy { it.kickoff }) {
                    box.addView(matchRow(m, m.id in pins, m.home.id in favs || m.away.id in favs, showDate = true), lp(MATCH, WRAP, top = dp(8)))
                }
            }
            return
        }
        val sorted = list.sortedWith(compareBy({ it.id !in pins }, { it.comp }, { it.kickoff }))
        if (sorted.isEmpty()) {
            box.addView(text(L.t(if (sc == "mine") "no_matches_mine" else "no_matches"), 13f, Ui.MUTED), lp(MATCH, WRAP, top = dp(12)))
        }
        if (sc != "all" && sorted.size < all.size) {
            box.addView(link(L.f("hidden_n", all.size - sorted.size)) {
                // Just this once: the next visit is back to "My teams".
                showAllOnce = true
                draw()
            }, lp(WRAP, WRAP, top = dp(4)))
        }
        var lastComp = ""
        for (m in sorted.take(60)) {
            if (m.comp != lastComp) {
                lastComp = m.comp
                box.addView(text(m.comp, 12f, Ui.ACCENT, bold = true), lp(MATCH, WRAP, top = dp(14)))
            }
            box.addView(matchRow(m, m.id in pins, m.home.id in favs || m.away.id in favs), lp(MATCH, WRAP, top = dp(8)))
        }
        if (sorted.size > 60) box.addView(text(L.f("more_n", sorted.size - 60), 12f, Ui.MUTED), lp(MATCH, WRAP, top = dp(8)))
    }

    private fun matchRow(m: Match, pinned: Boolean, mine: Boolean, showDate: Boolean = false): View {
        val r = row().apply {
            background = if (pinned) Ui.rounded(Ui.CARD, dp(16).toFloat(), Ui.ACCENT, dp(2)) else Ui.rounded(Ui.CARD, dp(16).toFloat())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            // The whole row opens the match page; the pin button keeps its own tap.
            isClickable = true
            setOnClickListener {
                try {
                    startActivity(MatchActivity.intent(this@MainActivity, m))
                } catch (e: Exception) {
                    // No browser.
                }
            }
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
            Api.PRE -> android.text.format.DateFormat.format(if (showDate) "EEE d/M\nh:mm a" else "h:mm a", m.kickoff).toString()
            Api.POST -> L.t("ft")
            else -> if (m.clock == Api.HALF_TIME) L.t("half_time") else m.clock
        }
        val clockColor = when {
            m.state == Api.LIVE && m.phase == "break" -> Ui.AMBER
            m.state == Api.LIVE -> Ui.ACCENT
            else -> Ui.MUTED
        }
        side.addView(text(clock, 13f, clockColor, bold = true).apply { gravity = Gravity.CENTER })
        if (m.state != Api.POST) {
            // One clear switch: is this match on the notch? Your team's: on unless you hid it.
            // Anyone else's: off unless you add it.
            val hidden = m.id in prefs.hiddenMatches
            val on = if (mine) !hidden else pinned
            val btn = text(L.t(if (on) "on_notch" else if (mine) "hidden_notch" else "show_notch"), 10.5f, if (on) Ui.ACCENT else Ui.MUTED, bold = true).apply {
                gravity = Gravity.CENTER
                background = if (on) Ui.rounded(Ui.ACCENT_SOFT, dp(10).toFloat()) else Ui.rounded(0, dp(10).toFloat(), Ui.LINE, dp(1))
                setPadding(dp(4), dp(6), dp(4), dp(6))
                isClickable = true
                setOnClickListener {
                    if (mine) {
                        prefs.hiddenMatches = if (hidden) prefs.hiddenMatches - m.id else prefs.hiddenMatches + m.id
                    } else {
                        prefs.pinned = if (pinned) prefs.pinned - m.id else prefs.pinned + m.id
                    }
                    LiveService.start(this@MainActivity, LiveService.ACTION_REFRESH)
                    drawList()
                }
            }
            side.addView(btn, lp(MATCH, WRAP, top = dp(8)))
        } else if (mine) {
            side.addView(text(L.t("your_team"), 10f, Ui.AMBER, bold = true).apply {
                gravity = Gravity.CENTER
                background = Ui.rounded(Ui.AMBER_SOFT, dp(6).toFloat())
                setPadding(dp(6), dp(3), dp(6), dp(3))
            }, lp(WRAP, WRAP, top = dp(8)))
        }
        r.addView(side, lp(dp(84), WRAP, start = dp(10)))
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

        // 1.9: today, the whole day (not just matches): data exactly, battery as an estimate.
        val cards = row().apply { gravity = Gravity.TOP }
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
        val bytes = prefs.usedOn(todayKey)
        val dataCard = statCard(
            "down", Ui.BLUE, L.t("data_today"),
            String.format(Locale.US, "%.1f", bytes / 1_048_576.0),
            L.t("mb"), L.t("exact"), Ui.ACCENT,
        )
        // 1.9.1: no battery percentage (Android gives no app its own share, and a guess misled):
        // the app's own processor time today, measured, with the phone's battery page below.
        val cpuMin = (prefs.dailyCpu[todayKey] ?: 0L) / 60_000.0
        val batCard = statCard(
            "bolt", Ui.AMBER, L.t("active_today"),
            String.format(Locale.US, if (cpuMin < 10) "%.1f" else "%.0f", cpuMin),
            L.t("minutes"), L.t("exact"), Ui.ACCENT,
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
        val values = days.map { (k, _) -> prefs.usedOn(k) }
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
        c.addView(text(L.t("battery_note2"), 11f, Ui.MUTED).apply { setLineSpacing(0f, 1.25f) }, lp(MATCH, WRAP, top = dp(12)))
        c.addView(button(L.t("battery_page")) {
            try {
                startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
            } catch (e: Exception) {
                appDetails()
            }
        }, lp(MATCH, WRAP, top = dp(8)))
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
        n.addView(toggleRow(L.t("roomy"), L.t("roomy_d"), prefs.roomyFullscreen) {
            prefs.roomyFullscreen = it
            LiveService.start(this, LiveService.ACTION_REFRESH)
        })
        n.addView(divider())
        n.addView(toggleRow(L.t("football"), L.t("football_d"), prefs.football) {
            if (it) startFootballWizard() else {
                prefs.football = false
                prefs.idleNotch = true
                LiveService.start(this, LiveService.ACTION_REFRESH)
                draw()
            }
        })
        n.addView(divider())
        n.addView(toggleRow(L.t("idle"), L.t("idle_d"), prefs.idleNotch) {
            prefs.idleNotch = it
            LiveService.start(this, LiveService.ACTION_REFRESH)
            draw()
        })
        if (prefs.idleNotch) {
            n.addView(divider())
            n.addView(navRow(L.t("slots"), L.f("slots_n", Shortcuts.load(this).count { it != null })) {
                page = "slots"
                draw()
            })
            n.addView(divider())
            n.addView(navRow(L.t("idle_content"), "") {
                page = "idle_content"
                draw()
            })
        }
        n.addView(divider())
        // Why it's a circle right now (so a stuck circle explains itself), and the apps it learned from swipes.
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val why = SystemPill.reason(this, am, am.mode == android.media.AudioManager.MODE_IN_CALL || am.mode == android.media.AudioManager.MODE_IN_COMMUNICATION)
        val learned = prefs.learnedPill
        val circleNote = L.t("circle_d") + "\n" + (if (why.isEmpty()) L.t("why_none") else L.f("why_now", why)) +
            (if (learned.isNotEmpty()) "\n" + L.f("why_list", learned.joinToString("، ") { SystemPill.label(this, it) }) else "")
        n.addView(toggleRow(L.t("circle"), circleNote, prefs.circle) { prefs.circle = it })
        if (learned.isNotEmpty()) {
            n.addView(navRow(L.t("forget_learned"), "") {
                prefs.learnedPill = emptySet()
                prefs.pillVotes = emptyMap()
                toast(L.t("forgot_learned"))
                LiveService.start(this, LiveService.ACTION_REFRESH)
                draw()
            })
        }
        n.addView(divider())
        n.addView(toggleRow(L.t("big_events"), L.t("big_events_d"), prefs.bigEvents) {
            prefs.bigEvents = it
            LiveService.start(this, LiveService.ACTION_REFRESH)
        })
        c.addView(n, lp(MATCH, WRAP, top = dp(8)))

        // Live activities: rides, deliveries and (without the phone's own pill) calls, timers, music.
        c.addView(label(L.t("live_acts")), lp(MATCH, WRAP, top = dp(16)))
        val la = card(0).apply { setPadding(dp(14), dp(2), dp(14), dp(12)) }
        la.addView(toggleRow(L.t("live_acts_on"), L.t("live_acts_d"), prefs.liveActs) {
            prefs.liveActs = it
            if (it && !listenerGranted()) {
                toast(L.t("phone_perm"))
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            draw()
        })
        if (prefs.liveActs) {
            la.addView(divider())
            la.addView(navRow(L.t("live_apps"), L.f("phone_apps_n", prefs.liveApps.size)) {
                pickApps(prefs.liveApps, Activities.SUGGESTED) { prefs.liveApps = it }
            })
            val pill = SystemPill.pillOn(this)
            la.addView(divider())
            la.addView(toggleRow(L.t("live_system"), if (pill) L.t("live_has_pill") else L.t("live_system_d"), prefs.liveSystem) {
                prefs.liveSystem = it
                if (it) askCallPerm(true)
            })
            la.addView(divider())
            la.addView(toggleRow(L.t("live_media"), if (pill) L.t("live_has_pill") else null, prefs.liveMedia) { prefs.liveMedia = it })
            la.addView(divider())
            la.addView(text(L.t("pill_mode"), 13f, Ui.MUTED), lp(MATCH, WRAP, top = dp(8)))
            la.addView(segmented(listOf("auto" to "pill_auto", "yes" to "pill_yes", "no" to "pill_no"), prefs.pillMode) {
                prefs.pillMode = it
                LiveService.start(this, LiveService.ACTION_REFRESH)
                draw()
            }, lp(MATCH, WRAP, top = dp(6)))
            la.addView(text(L.t("pill_mode_d"), 11f, Ui.MUTED), lp(MATCH, WRAP, top = dp(6)))
        }
        c.addView(la, lp(MATCH, WRAP, top = dp(8)))

        c.addView(label(L.t("alerts")), lp(MATCH, WRAP, top = dp(16)))
        val a = card(0).apply { setPadding(dp(14), dp(2), dp(14), dp(2)) }
        a.addView(toggleRow(L.t("sound"), null, prefs.sound) { prefs.sound = it })
        a.addView(divider())
        a.addView(navRow(L.t("sounds"), "") {
            page = "sounds"
            draw()
        })
        a.addView(divider())
        a.addView(toggleRow(L.t("vibrate"), null, prefs.vibrate) { prefs.vibrate = it })
        a.addView(divider())
        a.addView(toggleRow(L.t("news"), L.t("news_d"), prefs.news) {
            prefs.news = it
            draw()
        })
        if (prefs.news) {
            a.addView(text(L.t("news_lang"), 13f, Ui.MUTED), lp(MATCH, WRAP, top = dp(4)))
            a.addView(segmented(listOf("app" to "nl_app", "ar" to "_العربية", "en" to "_English"), prefs.newsLang) {
                prefs.newsLang = it
                draw()
            }, lp(MATCH, WRAP, top = dp(6)))
            a.addView(View(this), lp(MATCH, dp(10)))
        }
        a.addView(divider())
        a.addView(toggleRow(L.t("cards"), L.t("cards_d"), prefs.cards) { prefs.cards = it })
        c.addView(a, lp(MATCH, WRAP, top = dp(8)))

        // Beta: the notification inspector (gets 1.6 ready: rides, maps, timers in the notch).
        val ih = row()
        ih.addView(label(L.t("inspector")))
        ih.addView(text(L.t("beta"), 10f, Ui.BLUE, bold = true).apply {
            background = Ui.rounded(Ui.BLUE_SOFT, dp(6).toFloat())
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }, lp(WRAP, WRAP, start = dp(8)))
        c.addView(ih, lp(MATCH, WRAP, top = dp(16)))
        val ins = card(0).apply { setPadding(dp(14), dp(2), dp(14), dp(12)) }
        ins.addView(toggleRow(L.t("inspector_on"), null, prefs.inspector) {
            prefs.inspector = it
            if (it && !listenerGranted()) {
                toast(L.t("phone_perm"))
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            draw()
        })
        if (prefs.inspector) {
            ins.addView(divider())
            ins.addView(navRow(L.t("phone_apps"), L.f("phone_apps_n", prefs.inspectorApps.size)) {
                pickApps(prefs.inspectorApps) { prefs.inspectorApps = it }
            })
            ins.addView(divider())
            val log = NotifListener.logFile(this)
            ins.addView(navRow(L.t("inspector_share"), if (log.exists()) "${log.length() / 1024} KB" else "") { shareLog() })
            ins.addView(divider())
            ins.addView(navRow(L.t("inspector_clear"), "") {
                log.delete()
                draw()
            })
        }
        ins.addView(text(L.t("inspector_d"), 11f, Ui.MUTED), lp(MATCH, WRAP, top = dp(4)))
        c.addView(ins, lp(MATCH, WRAP, top = dp(8)))

        c.addView(label(L.t("permissions")), lp(MATCH, WRAP, top = dp(16)))
        val pr = card(0).apply { setPadding(dp(14), dp(2), dp(14), dp(2)) }
        pr.addView(navRow(L.t("setup_again"), "") {
            wizard = null
            onboarding = true
            step = stepIds().indexOf("overlay")
            drawOnboarding()
        })
        c.addView(pr, lp(MATCH, WRAP, top = dp(8)))
        c.addView(text(L.t("quick_tile"), 11f, Ui.MUTED), lp(MATCH, WRAP, top = dp(10)))

        // 1.9: updates install by themselves where Android allows; else the notch offers them.
        val up = card(0).apply { setPadding(dp(14), dp(2), dp(14), dp(2)) }
        up.addView(toggleRow(L.t("auto_update"), L.t("auto_update_d"), prefs.autoUpdate) { prefs.autoUpdate = it })
        c.addView(up, lp(MATCH, WRAP, top = dp(12)))

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

    /** Where a search page goes back to (the teams or competitions page, or the setup). */
    private var backTo: String? = null

    private fun goBack() {
        val p = page
        page = if (p == "device") "position" else if (p != null && (p.startsWith("search") || p == "slot_pick" || p == "city")) backTo else null
        if (p != null && p.startsWith("search")) {
            main.removeCallbacks(searchRun)
            searchBox = null
            currentFocus?.let { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0) }
        }
        draw()
    }

    private fun backRow(c: LinearLayout, title: String) {
        val r = row().apply {
            minimumHeight = dp(48)
            isClickable = true
            contentDescription = L.t("back")
            setOnClickListener { goBack() }
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
        // 1.8: the layout, chosen by itself from the room the status bar leaves (or picked here).
        k.addView(label(L.t("tpl")), lp(MATCH, WRAP, top = dp(12)))
        k.addView(segmented(listOf("auto" to "tpl_auto", "A" to "_A", "B" to "_B", "C" to "_C", "D" to "_D"), prefs.template) {
            prefs.template = it
            LiveService.start(this, LiveService.ACTION_REFRESH)
            LiveService.start(this, LiveService.ACTION_DEMO, "preview")
            draw()
        }, lp(MATCH, WRAP, top = dp(8)))
        k.addView(text(L.t("tpl_d"), 11f, Ui.MUTED).apply { setLineSpacing(0f, 1.25f) }, lp(MATCH, WRAP, top = dp(6)))
        k.addView(slider(L.t("up_down"), -20, 20, prefs.offsetY, 0) { prefs.offsetY = it }, lp(MATCH, WRAP, top = dp(12)))
        k.addView(slider(L.t("size"), 50, 140, prefs.scale, 100) { prefs.scale = it }, lp(MATCH, WRAP, top = dp(8)))
        k.addView(slider(L.t("width_pct"), 40, 130, prefs.widthPct, 100) { prefs.widthPct = it }, lp(MATCH, WRAP, top = dp(8)))
        k.addView(text(L.t("width_pct_d"), 11f, Ui.MUTED), lp(MATCH, WRAP, top = dp(2)))
        c.addView(k, lp(MATCH, WRAP, top = dp(12)))

        // The always-on notch has its own place (1.8): moving one no longer moves the other.
        c.addView(label(L.t("idle_pos")), lp(MATCH, WRAP, top = dp(16)))
        val k2 = card()
        k2.addView(toggleRow(L.t("link_pos"), L.t("link_pos_d"), prefs.linkPos) {
            prefs.linkPos = it
            LiveService.start(this, LiveService.ACTION_REFRESH)
            draw()
        })
        if (!prefs.linkPos) k2.addView(slider(L.t("up_down"), -20, 20, prefs.idleOffsetY, 0, preview = "idle") { prefs.idleOffsetY = it }, lp(MATCH, WRAP, top = dp(8)))
        k2.addView(slider(L.t("idle_scale"), 50, 150, prefs.idleScale, 90, preview = "idle") { prefs.idleScale = it }, lp(MATCH, WRAP, top = dp(8)))
        k2.addView(slider(L.t("idle_size"), 50, 150, prefs.idleSize, 100, preview = "idle") { prefs.idleSize = it }, lp(MATCH, WRAP, top = dp(8)))
        c.addView(k2, lp(MATCH, WRAP, top = dp(8)))
        c.addView(button(L.t("reset_pos")) {
            prefs.offsetY = 0
            prefs.offsetX = 0
            prefs.scale = 100
            prefs.widthExtra = 0
            prefs.widthPct = 100
            prefs.template = "auto"
            prefs.idleOffsetY = 0
            prefs.linkPos = false
            prefs.idleSize = 100
            prefs.idleScale = 90
            LiveService.start(this, LiveService.ACTION_REFRESH)
            LiveService.start(this, LiveService.ACTION_DEMO, "preview")
            draw()
        }, lp(MATCH, WRAP, top = dp(10)))
        c.addView(card(0).apply {
            setPadding(dp(14), dp(2), dp(14), dp(2))
            addView(navRow(L.t("device_info"), "") {
                page = "device"
                draw()
            })
        }, lp(MATCH, WRAP, top = dp(8)))

        c.addView(label(L.t("try")), lp(MATCH, WRAP, top = dp(16)))
        val demos = listOf("goal" to "d_goal", "red" to "d_red", "whistle" to "d_whistle", "lineups" to "d_lineups", "against" to "d_against", "news" to "d_news", "info" to "d_info", "grid" to "d_grid", "none" to "d_none")
        for (rowItems in demos.chunked(3)) {
            val r = row()
            rowItems.forEachIndexed { i, (kind, key) ->
                r.addView(button(L.t(key), primary = kind == "goal") { LiveService.start(this, LiveService.ACTION_DEMO, kind) }, lp(0, WRAP, 1f, start = if (i > 0) dp(8) else 0))
            }
            c.addView(r, lp(MATCH, WRAP, top = dp(8)))
        }
        c.addView(text(L.t("try_note"), 12f, Ui.MUTED), lp(MATCH, WRAP, top = dp(10)))
    }

    /**
     * A labelled slider with its own ↺ (back to [def]). On release it saves, re-applies the notch and
     * shows [preview] ("preview": the match notch for 4 s when nothing is live; "idle": the empty one).
     */
    private fun slider(title: String, lo: Int, hi: Int, value: Int, def: Int? = null, preview: String? = "preview", save: (Int) -> Unit): View {
        val box = column()
        val head = row()
        val t = text("$title: $value", 13f, Ui.MUTED)
        head.addView(t, lp(0, WRAP, 1f))
        lateinit var bar: SeekBar
        fun apply(v: Int) {
            save(v)
            if (preview != null) {
                LiveService.start(this@MainActivity, LiveService.ACTION_REFRESH)
                LiveService.start(this@MainActivity, LiveService.ACTION_DEMO, preview)
            }
        }
        if (def != null) {
            head.addView(text("↺", 18f, Ui.MUTED).apply {
                gravity = Gravity.CENTER
                contentDescription = L.t("reset_one")
                isClickable = true
                setOnClickListener {
                    bar.progress = def - lo
                    t.text = "$title: $def"
                    apply(def)
                }
            }, lp(dp(40), dp(32)))
        }
        box.addView(head)
        bar = SeekBar(this).apply {
            max = hi - lo
            progress = value - lo
            progressTintList = ColorStateList.valueOf(Ui.ACCENT)
            thumbTintList = ColorStateList.valueOf(Ui.ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    t.text = "$title: ${p + lo}"
                }

                override fun onStartTrackingTouch(s: SeekBar) {}

                override fun onStopTrackingTouch(s: SeekBar) = apply(s.progress + lo)
            })
        }
        box.addView(bar, lp(MATCH, dp(44), top = dp(4)))
        return box
    }

    /* ----- sub-page (and onboarding step 1): favourite teams ----- */

    private fun teamsPage(c: LinearLayout) {
        backRow(c, L.t("teams"))
        teamsEditor(c)
    }

    /** The favourites, highest priority first: ↑ ↓ to reorder, × to remove, + to search. */
    private fun teamsEditor(c: LinearLayout) {
        val k = card()
        val teams = prefs.teams
        // A list you rearrange by long-pressing a team and dragging it.
        val list = DragGrid(this, 1, dp(2), swap = false, onMove = { f, t ->
            val l = prefs.teams.toMutableList()
            l.add(t, l.removeAt(f))
            prefs.teams = l
            LiveService.start(this, LiveService.ACTION_REFRESH)
            draw()
        })
        for ((i, t) in teams.withIndex()) {
            val r = orderRow(t, i, teams.size, null) {
                prefs.teams = prefs.teams.filter { it.id != t.id }
                LiveService.start(this, LiveService.ACTION_REFRESH)
                draw()
            }
            r.background = Ui.rounded(Ui.CARD, dp(12).toFloat())
            (r as LinearLayout).addView(text("≡", 18f, Ui.MUTED).apply { gravity = Gravity.CENTER }, 0, lp(dp(24), dp(44)))
            list.addCell(r)
        }
        k.addView(list, lp(MATCH, WRAP))
        if (teams.isEmpty()) k.addView(text(L.t("no_teams"), 13f, Ui.MUTED))
        k.addView(button("+  " + L.t("add_team"), primary = true) {
            backTo = page
            searchQuery = ""
            page = "search_team"
            draw()
        }, lp(MATCH, WRAP, top = dp(12)))
        c.addView(k, lp(MATCH, WRAP, top = dp(12)))
        if (teams.size > 1) c.addView(text(L.t("order_note"), 12f, Ui.MUTED), lp(MATCH, WRAP, top = dp(8)))
    }

    /** One team or competition: crest, name, and (for teams) ↑ ↓, then ×. */
    private fun orderRow(t: TeamOption, i: Int, n: Int, move: ((Int) -> Unit)?, remove: () -> Unit): View {
        val r = row().apply { minimumHeight = dp(52) }
        r.addView(crest(t.logo, 28))
        r.addView(text(t.name, 14f, bold = true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, lp(0, WRAP, 1f, start = dp(10)))
        fun small(label: String, desc: String, enabled: Boolean, color: Int, act: () -> Unit) = text(label, 16f, if (enabled) color else Ui.LINE, bold = true).apply {
            gravity = Gravity.CENTER
            contentDescription = desc
            isClickable = enabled
            if (enabled) setOnClickListener { act() }
        }
        if (move != null) {
            r.addView(small("↑", L.t("move_up"), i > 0, Ui.TEXT) { move(-1) }, lp(dp(40), dp(44)))
            r.addView(small("↓", L.t("move_down"), i < n - 1, Ui.TEXT) { move(1) }, lp(dp(40), dp(44)))
        }
        r.addView(small("×", L.t("remove"), true, Ui.RED) { remove() }, lp(dp(40), dp(44)))
        return r
    }

    /* ----- sub-page: followed competitions ----- */

    private fun compsPage(c: LinearLayout) {
        backRow(c, L.t("comps"))
        c.addView(text(L.t("comps_note"), 13f, Ui.MUTED), lp(MATCH, WRAP, top = dp(8)))
        compsEditor(c)
    }

    private fun compsEditor(c: LinearLayout) {
        val k = card()
        val comps = prefs.comps
        comps.forEachIndexed { i, t ->
            if (i > 0) k.addView(divider())
            k.addView(orderRow(t, i, comps.size, null) {
                prefs.comps = prefs.comps.filter { it.id != t.id }
                draw()
            })
        }
        if (comps.isEmpty()) k.addView(text(L.t("no_comps"), 13f, Ui.MUTED))
        k.addView(button("+  " + L.t("add_comp"), primary = true) {
            backTo = page
            searchQuery = ""
            page = "search_comp"
            draw()
        }, lp(MATCH, WRAP, top = dp(12)))
        c.addView(k, lp(MATCH, WRAP, top = dp(12)))
    }

    /* ----- sub-page: search a team or a competition, results while typing ----- */

    private var searchQuery = ""
    private val searchRun = Runnable { runSearch() }
    private var searchBox: LinearLayout? = null
    private var searchComps = false
    private var searchSeq = 0

    private fun searchPage(c: LinearLayout, comps: Boolean) {
        searchComps = comps
        backRow(c, L.t(if (comps) "add_comp" else "add_team"))
        // The field sits at the top, so the keyboard never covers it.
        val field = row().apply {
            background = Ui.rounded(Ui.CARD, dp(14).toFloat(), Ui.ACCENT, dp(1))
            setPadding(dp(14), 0, dp(14), 0)
            minimumHeight = dp(50)
        }
        field.addView(Icon(this, "search", 18).apply { color = Ui.ACCENT })
        val box = EditText(this).apply {
            hint = L.t(if (comps) "search_comp_hint" else "search_team_hint")
            setText(searchQuery)
            setSelection(searchQuery.length)
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 15f
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    searchQuery = s?.toString()?.trim() ?: ""
                    // Searches by itself a moment after the last letter.
                    main.removeCallbacks(searchRun)
                    main.postDelayed(searchRun, 450)
                }
            })
            setOnEditorActionListener { _, _, _ ->
                main.removeCallbacks(searchRun)
                runSearch()
                true
            }
        }
        field.addView(box, lp(0, WRAP, 1f, start = dp(8)))
        c.addView(field, lp(MATCH, WRAP, top = dp(12)))
        searchBox = column()
        c.addView(searchBox, lp(MATCH, WRAP, top = dp(8)))
        box.requestFocus()
        box.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(box, 0)
        }, 150)
        runSearch()
    }

    private fun runSearch() {
        val out = searchBox ?: return
        val q = searchQuery
        val comps = searchComps
        val seq = ++searchSeq
        out.removeAllViews()
        if (q.length < 2) {
            out.addView(text(L.t("type_more"), 12f, Ui.MUTED), lp(MATCH, WRAP, top = dp(8)))
            return
        }
        out.addView(text(L.t("searching"), 12f, Ui.MUTED), lp(MATCH, WRAP, top = dp(8)))
        io.execute {
            val found = try {
                if (comps) Api.searchComps(q) else Api.search(q)
            } catch (e: Exception) {
                null
            }
            main.post {
                if (seq != searchSeq || searchBox !== out) return@post
                out.removeAllViews()
                when {
                    found == null -> out.addView(text(L.t("no_net"), 12f, Ui.RED), lp(MATCH, WRAP, top = dp(8)))
                    found.isEmpty() -> out.addView(text(L.t("no_results"), 12f, Ui.MUTED), lp(MATCH, WRAP, top = dp(8)))
                }
                for (t in found ?: emptyList()) out.addView(resultRow(t, comps), lp(MATCH, WRAP, top = dp(6)))
            }
        }
    }

    private fun resultRow(t: TeamOption, comps: Boolean): View {
        fun have() = (if (comps) prefs.comps else prefs.teams).any { it.id == t.id }
        val r = row().apply {
            background = Ui.rounded(Ui.CARD, dp(14).toFloat())
            setPadding(dp(12), dp(8), dp(8), dp(8))
            minimumHeight = dp(56)
        }
        r.addView(crest(t.logo, 30))
        val names = column()
        names.addView(text(t.name, 14f, bold = true).apply { maxLines = 1 })
        if (t.sub.isNotBlank()) names.addView(text(t.sub, 12f, Ui.MUTED).apply { maxLines = 1 })
        r.addView(names, lp(0, WRAP, 1f, start = dp(10)))
        val btn = text(L.t(if (have()) "added" else "add"), 13f, if (have()) Ui.MUTED else Ui.ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(40)
            setPadding(dp(12), 0, dp(12), 0)
        }
        r.addView(btn)
        r.isClickable = true
        r.setOnClickListener {
            if (have()) return@setOnClickListener
            val clean = t.copy(sub = "")
            if (comps) prefs.comps = prefs.comps + clean else prefs.teams = prefs.teams + clean
            LiveService.start(this, LiveService.ACTION_REFRESH)
            btn.text = L.t("added")
            btn.setTextColor(Ui.MUTED)
        }
        return r
    }

    /* ----- sub-pages: what the always-on notch shows, and the city for weather / prayers ----- */

    private fun idleContentPage(c: LinearLayout) {
        backRow(c, L.t("idle_content"))
        c.addView(text(L.t("idle_content_d"), 13f, Ui.MUTED), lp(MATCH, WRAP, top = dp(8)))
        val k = card(0).apply { setPadding(dp(14), dp(2), dp(14), dp(2)) }
        val show = prefs.idleShow
        listOf("weather", "date", "hijri", "prayer", "match").forEachIndexed { i, key ->
            if (i > 0) k.addView(divider())
            k.addView(toggleRow(L.t("ic_$key"), null, key in show) { on ->
                prefs.idleShow = if (on) prefs.idleShow + key else prefs.idleShow - key
                if (on && key == "weather") io.execute {
                    try {
                        Ambient.refreshWeather(Locator.city(this, prefs))
                    } catch (e: Exception) {
                        // Next time.
                    }
                }
                LiveService.start(this, LiveService.ACTION_DEMO, "idle")
            })
        }
        c.addView(k, lp(MATCH, WRAP, top = dp(12)))
        // 1.9: the weather and the prayers follow the phone (or a chosen district).
        c.addView(label(L.t("place")), lp(MATCH, WRAP, top = dp(16)))
        locationCard(c)
        val wa = card(0).apply { setPadding(dp(14), dp(2), dp(14), dp(2)) }
        wa.addView(toggleRow(L.t("w_alerts"), L.t("w_alerts_d"), prefs.weatherAlerts) {
            prefs.weatherAlerts = it
            LiveService.start(this, LiveService.ACTION_REFRESH)
        })
        c.addView(wa, lp(MATCH, WRAP, top = dp(8)))
        c.addView(label(L.t("weather_anim")), lp(MATCH, WRAP, top = dp(16)))
        c.addView(segmented(listOf("auto" to "wa_auto", "always" to "wa_always", "off" to "wa_off"), prefs.weatherAnim) {
            prefs.weatherAnim = it
            draw()
            LiveService.start(this, LiveService.ACTION_DEMO, "idle")
        }, lp(MATCH, WRAP, top = dp(8)))
        c.addView(text(L.t("wa_note"), 11f, Ui.MUTED), lp(MATCH, WRAP, top = dp(6)))

        // Try each weather on the notch (5 s), whatever it is outside.
        c.addView(label(L.t("try")), lp(MATCH, WRAP, top = dp(16)))
        val kinds = listOf("sun", "clouds", "rain", "storm", "snow", "fog", "wind", "dust", "cold", "hot")
        for (rowItems in kinds.chunked(4)) {
            val r = row()
            rowItems.forEachIndexed { i, k ->
                r.addView(button(L.t("w_$k")) {
                    LiveService.start(this, LiveService.ACTION_DEMO, "w_$k")
                }, lp(0, WRAP, 1f, start = if (i > 0) dp(6) else 0))
            }
            for (i in rowItems.size until 4) r.addView(View(this), lp(0, WRAP, 1f, start = dp(6)))
            c.addView(r, lp(MATCH, WRAP, top = dp(8)))
        }
        c.addView(button(L.t("try_alert")) { LiveService.start(this, LiveService.ACTION_DEMO, "alert") }, lp(MATCH, WRAP, top = dp(8)))
    }

    /** City search (Open-Meteo): the weather and prayer times follow it. */
    private fun cityPage(c: LinearLayout) {
        backRow(c, L.t("city"))
        val field = EditText(this).apply {
            hint = L.t("city_hint")
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 15f
            background = Ui.rounded(Ui.CARD, dp(14).toFloat(), Ui.ACCENT, dp(1))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        c.addView(field, lp(MATCH, WRAP, top = dp(12)))
        val out = column()
        c.addView(out, lp(MATCH, WRAP, top = dp(8)))
        var seq = 0
        val run = Runnable {
            val q = field.text.toString().trim()
            val my = ++seq
            out.removeAllViews()
            if (q.length < 2) {
                out.addView(text(L.t("type_more"), 12f, Ui.MUTED))
                return@Runnable
            }
            out.addView(text(L.t("searching"), 12f, Ui.MUTED))
            io.execute {
                val found = try {
                    Ambient.searchCities(q)
                } catch (e: Exception) {
                    null
                }
                main.post {
                    if (my != seq) return@post
                    out.removeAllViews()
                    if (found == null) out.addView(text(L.t("no_net"), 12f, Ui.RED))
                    else if (found.isEmpty()) out.addView(text(L.t("no_results"), 12f, Ui.MUTED))
                    for ((city, where) in found ?: emptyList()) {
                        val r = column().apply {
                            background = Ui.rounded(Ui.CARD, dp(14).toFloat())
                            setPadding(dp(14), dp(10), dp(14), dp(10))
                            isClickable = true
                            setOnClickListener {
                                prefs.city = city
                                io.execute {
                                    try {
                                        Ambient.refreshWeather(city)
                                    } catch (e: Exception) {
                                        // Next time.
                                    }
                                }
                                toast(L.t("slot_saved"))
                                goBack()
                            }
                        }
                        r.addView(text(city.name, 14f, bold = true))
                        if (where.isNotBlank()) r.addView(text(where, 12f, Ui.MUTED))
                        out.addView(r, lp(MATCH, WRAP, top = dp(6)))
                    }
                }
            }
        }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                main.removeCallbacks(run)
                main.postDelayed(run, 450)
            }
        })
        field.requestFocus()
    }

    /* ----- sub-pages: the always-on notch's shortcut slots ----- */

    /** Which slot the picker fills. */
    private var pickIndex = 0
    private var pickType = "action"

    private fun slotsPage(c: LinearLayout) {
        backRow(c, L.t("slots"))
        c.addView(text(L.t("slots_note"), 13f, Ui.MUTED), lp(MATCH, WRAP, top = dp(8)))
        val slots = Shortcuts.load(this)
        // Long-press a slot and drop it on another: the two trade places. × empties one.
        val grid = DragGrid(this, 4, dp(8), swap = true, onMove = { f, t ->
            val l = Shortcuts.load(this).toMutableList()
            val x = l[f]
            l[f] = l[t]
            l[t] = x
            Shortcuts.save(this, l)
            draw()
        })
        for (i in 0 until Shortcuts.SLOTS) {
            val s = slots[i]
            val frame = FrameLayout(this)
            val cell = column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                background = Ui.rounded(Ui.CARD, dp(16).toFloat())
                setPadding(dp(4), dp(10), dp(4), dp(10))
                isClickable = true
                setOnClickListener {
                    pickIndex = i
                    backTo = "slots"
                    page = "slot_pick"
                    draw()
                }
                setOnLongClickListener { frame.performLongClick() }
            }
            cell.addView(slotIcon(s), lp(dp(32), dp(32)))
            cell.addView(text(s?.let { Shortcuts.label(this, it) } ?: "+", 11f, if (s == null) Ui.MUTED else Ui.TEXT).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, lp(MATCH, WRAP, top = dp(6)))
            frame.addView(cell, FrameLayout.LayoutParams(MATCH, MATCH))
            if (s != null) {
                frame.addView(text("×", 14f, 0xFFFFFFFF.toInt(), bold = true).apply {
                    tag = "x"
                    gravity = Gravity.CENTER
                    background = Ui.rounded(0xFFEF4444.toInt(), dp(11).toFloat())
                    isClickable = true
                    setOnClickListener {
                        Shortcuts.set(this@MainActivity, i, null)
                        draw()
                    }
                }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END))
            }
            grid.addCell(frame)
        }
        c.addView(grid, lp(MATCH, WRAP, top = dp(12)))
        c.addView(text(L.t("slots_drag"), 11f, Ui.MUTED), lp(MATCH, WRAP, top = dp(10)))
    }

    /** A slot's picture: the app icon, the site icon, or the action's symbol. */
    private fun slotIcon(s: Slot?): View {
        if (s == null) return text("+", 22f, Ui.MUTED).apply { gravity = Gravity.CENTER }
        if (s.type == "action") return text(Shortcuts.glyph(s.value), 20f).apply { gravity = Gravity.CENTER }
        val iv = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER }
        val bm = Shortcuts.icon(this, s) { Shortcuts.icon(this, s) {}?.let { iv.setImageBitmap(it) } }
        if (bm != null) iv.setImageBitmap(bm)
        return iv
    }

    /** Fills one slot: a built-in shortcut, an app, or a link. Also removes it. */
    private fun slotPickPage(c: LinearLayout) {
        backRow(c, L.f("slot_n", pickIndex + 1))
        val current = Shortcuts.load(this).getOrNull(pickIndex)
        val done = { s: Slot? ->
            Shortcuts.set(this, pickIndex, s)
            toast(L.t(if (s == null) "slot_removed" else "slot_saved"))
            goBack()
        }
        if (current != null) {
            val r = row().apply {
                background = Ui.rounded(Ui.CARD, dp(14).toFloat())
                setPadding(dp(12), dp(8), dp(8), dp(8))
            }
            r.addView(slotIcon(current), lp(dp(28), dp(28)))
            r.addView(text(Shortcuts.label(this, current), 14f, bold = true), lp(0, WRAP, 1f, start = dp(10)))
            r.addView(link(L.t("remove")) { done(null) }.apply { setTextColor(Ui.RED) })
            c.addView(r, lp(MATCH, WRAP, top = dp(10)))
            if (current.type == "action" && current.value == "record") {
                c.addView(navRow(L.t("rec_sound"), L.t("rs_" + prefs.recAudio)) { recSoundDialog { draw() } }, lp(MATCH, WRAP, top = dp(6)))
            }
        }
        c.addView(segmented(listOf("action" to "slot_action", "app" to "slot_app", "link" to "slot_link"), pickType) {
            pickType = it
            draw()
        }, lp(MATCH, WRAP, top = dp(14)))
        val list = column()
        c.addView(list, lp(MATCH, WRAP, top = dp(8)))
        fun choice(icon: View, title: String, sub: String?, onClick: () -> Unit) {
            val r = row().apply {
                minimumHeight = dp(54)
                isClickable = true
                setOnClickListener { onClick() }
            }
            r.addView(icon, lp(dp(30), dp(30)))
            val t = column()
            t.addView(text(title, 14f, bold = true))
            if (sub != null) t.addView(text(sub, 11f, Ui.MUTED))
            r.addView(t, lp(0, WRAP, 1f, start = dp(12)))
            list.addView(r)
        }
        when (pickType) {
            "action" -> for (a in Shortcuts.ACTIONS) {
                val sub = when {
                    Shortcuts.needsAccessibility(a) && TapService.instance == null -> L.t("sc_need_access")
                    else -> L.t("scd_$a").takeIf { it != "scd_$a" }
                }
                choice(text(Shortcuts.glyph(a), 20f).apply { gravity = Gravity.CENTER }, L.t("sc_$a"), sub) {
                    if (a == "record") recSoundDialog { done(Slot("action", a, "")) } else done(Slot("action", a, ""))
                }
            }
            "app" -> {
                val field = EditText(this).apply {
                    hint = L.t("search_app")
                    setHintTextColor(Ui.MUTED)
                    setTextColor(Ui.TEXT)
                    background = Ui.rounded(Ui.CARD, dp(12).toFloat())
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    inputType = InputType.TYPE_CLASS_TEXT
                }
                c.addView(field, c.indexOfChild(list), lp(MATCH, WRAP, top = dp(8)))
                val pm = packageManager
                val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                    .filter { it.first != packageName }
                    .distinctBy { it.first }
                    .sortedBy { it.second.lowercase() }
                fun fill() {
                    list.removeAllViews()
                    val q = field.text.toString().trim()
                    for ((pkg, name) in apps.filter { q.isEmpty() || it.second.contains(q, true) }.take(80)) {
                        val iv = android.widget.ImageView(this).apply { Shortcuts.appIcon(this@MainActivity, pkg)?.let { setImageBitmap(it) } }
                        choice(iv, name, null) { done(Slot("app", pkg, name)) }
                    }
                }
                field.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) = fill()
                })
                fill()
            }
            else -> {
                fun input(hintKey: String, type: Int) = EditText(this).apply {
                    hint = L.t(hintKey)
                    setHintTextColor(Ui.MUTED)
                    setTextColor(Ui.TEXT)
                    background = Ui.rounded(Ui.CARD, dp(12).toFloat())
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    inputType = type
                }
                val url = input("link_hint", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
                val name = input("link_name", InputType.TYPE_CLASS_TEXT)
                list.addView(url, lp(MATCH, WRAP))
                list.addView(name, lp(MATCH, WRAP, top = dp(8)))
                list.addView(button(L.t("save"), primary = true) {
                    val u = url.text.toString().trim()
                    if (u.length < 4 || !u.contains('.')) {
                        toast(L.t("link_bad"))
                        return@button
                    }
                    val full = Shortcuts.fixLink(u)
                    val label = name.text.toString().trim().ifBlank { Uri.parse(full).host?.removePrefix("www.") ?: u }
                    done(Slot("link", full, label))
                }, lp(MATCH, WRAP, top = dp(12)))
                list.addView(text(L.t("link_note"), 11f, Ui.MUTED), lp(MATCH, WRAP, top = dp(8)))
            }
        }
    }

    /** Screen recording sound: the phone's, the mic, both, or none. */
    private fun recSoundDialog(then: () -> Unit) {
        val keys = listOf("internal", "mic", "both", "none")
        android.app.AlertDialog.Builder(this)
            .setTitle(L.t("rec_sound"))
            .setSingleChoiceItems(keys.map { L.t("rs_$it") }.toTypedArray(), keys.indexOf(prefs.recAudio).coerceAtLeast(0)) { d, i ->
                prefs.recAudio = keys[i]
                d.dismiss()
                then()
            }
            .show()
    }

    /* ----- sub-page: this phone (1.8), to send when the notch looks wrong on some phone ----- */

    private fun deviceReport(): String {
        val dm = resources.displayMetrics
        val installer = try {
            if (Build.VERSION.SDK_INT >= 30) packageManager.getInstallSourceInfo(packageName).installingPackageName else @Suppress("DEPRECATION") packageManager.getInstallerPackageName(packageName)
        } catch (e: Exception) {
            null
        }
        val yes = { b: Boolean -> if (b) "✓" else "✗" }
        return buildString {
            append("Notch Live ${try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }}\n")
            append("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
            append("dpi ${dm.densityDpi} · font ${"%.2f".format(resources.configuration.fontScale)} · installer ${installer ?: "-"}\n")
            append(Island.lastReport.ifEmpty { "(notch not measured yet: show it once)" }).append("\n")
            append("template: ${prefs.template} → ${IslandView.lastTpl.ifEmpty { "-" }} · width ${prefs.widthPct}% · size ${prefs.scale}% · up/down ${prefs.offsetY}/${prefs.idleOffsetY}\n")
            append("overlay ${yes(Settings.canDrawOverlays(this@MainActivity))} · accessibility ${yes(TapService.instance != null)} · notifications access ${yes(listenerGranted())} · battery ${yes((getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName))} · restricted ${if (restrictedLikely()) "maybe" else "no"}")
        }
    }

    private fun devicePage(c: LinearLayout) {
        backRow(c, L.t("device_info"))
        c.addView(text(L.t("device_d"), 13f, Ui.MUTED).apply { setLineSpacing(0f, 1.25f) }, lp(MATCH, WRAP, top = dp(8)))
        val k = card()
        val report = deviceReport()
        k.addView(text(report, 12f).apply {
            setTextIsSelectable(true)
            textDirection = View.TEXT_DIRECTION_LTR
            setLineSpacing(0f, 1.3f)
        })
        c.addView(k, lp(MATCH, WRAP, top = dp(12)))
        c.addView(button(L.t("send_report"), primary = true) {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
            startActivity(Intent.createChooser(send, L.t("send_report")))
        }, lp(MATCH, dp(52), top = dp(12)))
    }

    /* ----- sub-page: sounds ----- */

    private fun soundsPage(c: LinearLayout) {
        backRow(c, L.t("sounds"))
        for (g in listOf("goal", "card", "whistle", "news")) {
            c.addView(label(L.t("snd_$g")), lp(MATCH, WRAP, top = dp(16)))
            c.addView(segmented(listOf("app" to "s_app", "phone" to "s_phone", "off" to "s_off"), prefs.soundFor(g)) {
                prefs.setSoundFor(g, it)
                draw()
            }, lp(MATCH, WRAP, top = dp(8)))
        }
        // 1.8: a goal against your team: its own quiet sound (default), like any goal, or silent.
        c.addView(label(L.t("snd_against")), lp(MATCH, WRAP, top = dp(16)))
        c.addView(segmented(listOf("calm" to "ag_calm", "same" to "ag_same", "off" to "s_off"), prefs.againstMode) {
            prefs.againstMode = it
            if (it != "same") {
                val s = Sounds(this)
                main.postDelayed({
                    if (it == "calm") s.play("conceded")
                    main.postDelayed({ s.release() }, 3000)
                }, 400)
            }
            draw()
        }, lp(MATCH, WRAP, top = dp(8)))
        c.addView(text(L.t("ag_note"), 11f, Ui.MUTED), lp(MATCH, WRAP, top = dp(6)))
        val k = card()
        k.addView(slider(L.t("volume"), 0, 100, prefs.volume, 80, preview = null) { prefs.volume = it }, lp(MATCH, WRAP))
        k.addView(button(L.t("test_sound")) {
            val s = Sounds(this)
            // SoundPool loads in the background: play once it's ready.
            main.postDelayed({
                s.play("goal")
                main.postDelayed({ s.release() }, 4000)
            }, 400)
        }, lp(MATCH, WRAP, top = dp(8)))
        k.addView(text(L.t("volume_note"), 11f, Ui.MUTED), lp(MATCH, WRAP, top = dp(8)))
        c.addView(k, lp(MATCH, WRAP, top = dp(16)))
    }

    /* ----- phone notifications: pick apps ----- */

    private fun listenerGranted(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    private fun shareLog() {
        val log = NotifListener.logFile(this)
        if (!log.exists() || log.length() == 0L) {
            toast(L.t("inspector_empty"))
            return
        }
        val copy = java.io.File(ShareCard.dir(this), "notch-inspector.txt")
        log.copyTo(copy, overwrite = true)
        val uri = Uri.parse("content://$packageName.share/${copy.name}")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri("", uri)
        startActivity(Intent.createChooser(send, L.t("inspector_share")))
    }

    private fun pickApps(current: Set<String>, first: List<String> = emptyList(), save: (Set<String>) -> Unit) {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedWith(compareBy({ if (it.first in first) 0 else 1 }, { it.second.lowercase() }))
        val chosen = current.toMutableSet()
        val checked = BooleanArray(apps.size) { apps[it].first in chosen }
        AlertDialog.Builder(this)
            .setTitle(L.t("pick_apps"))
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, on ->
                if (on) chosen += apps[i].first else chosen -= apps[i].first
            }
            .setPositiveButton(L.t("done")) { _, _ ->
                save(chosen)
                draw()
            }
            .show()
    }

    /** Answer / end calls from the notch: asked once on its own, and again whenever the switch is turned on. */
    private fun askCallPerm(fromSwitch: Boolean) {
        if (!prefs.liveActs || !prefs.liveSystem || SystemPill.pillOn(this)) return
        if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (!fromSwitch && prefs.askedCallPerm) return
        prefs.askedCallPerm = true
        toast(L.t("call_perm"))
        requestPermissions(arrayOf(Manifest.permission.ANSWER_PHONE_CALLS), 7)
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

    /*
     * 1.9: the steps by name. Football first (yes: teams and competitions; no: everything about it
     * off), then permissions, the notch's look on the real notch, the always-on notch and its
     * shortcuts, the place, and a check of every permission. Each step can be skipped, and from the
     * look step on the rest can be left for later.
     * [wizard]: only some steps (turning football on from the Matches page).
     */
    private var wizard: List<String>? = null
    private val REQ_LOC = 7
    private val REQ_LOC_BG = 8

    private fun stepIds(): List<String> = wizard ?: buildList {
        add("football")
        if (prefs.football) add("teams")
        // 1.9.1 (Omar's order): notifications, then restricted settings (its own step with a ✓),
        // then the permissions it unlocks.
        add("notif")
        if (restrictedNeeded()) add("restricted")
        addAll(listOf("overlay", "access", "battery", "look", "idle", "location", "summary"))
    }

    private val steps get() = stepIds().size
    private fun stepId(i: Int = step) = stepIds().getOrElse(i) { "summary" }

    private fun granted(s: Int): Boolean = grantedId(stepId(s))

    private fun grantedId(id: String): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return when (id) {
            "overlay" -> Settings.canDrawOverlays(this)
            "access" -> TapService.instance != null
            "battery" -> pm.isIgnoringBatteryOptimizations(packageName)
            "notif" -> Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            "location" -> !prefs.locAuto || Locator.canBg(this)
            "restricted" -> restrictedOk()
            else -> true
        }
    }

    private fun finishOnboarding() {
        val w = wizard
        wizard = null
        prefs.onboarded = true
        onboarding = false
        if (w != null) {
            page = null
            tab = 1
        }
        LiveService.start(this)
        LiveService.start(this, LiveService.ACTION_REFRESH)
        draw()
    }

    /** Matches page with football off → "turn it on": teams and competitions, then the notch's size. */
    private fun startFootballWizard() {
        prefs.football = true
        wizard = listOf("teams", "look")
        onboarding = true
        step = 0
        drawOnboarding()
    }

    private fun drawOnboarding() {
        if (step >= steps) {
            finishOnboarding()
            return
        }
        val id = stepId()
        root.removeAllViews()
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val c = column().apply { setPadding(dp(24), dp(28), dp(24), dp(24)) }
        scroll.addView(c)
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))

        val head = row()
        head.addView(text(L.f("step_of", step + 1, steps), 13f, Ui.MUTED), lp(0, WRAP, 1f))
        // "Skip" skips this step only.
        if (step < steps - 1) head.addView(link(L.t("skip_step")) {
            step++
            drawOnboarding()
        }.apply { setTextColor(Ui.MUTED) })
        c.addView(head)
        val dots = row()
        for (i in 0 until steps) {
            dots.addView(View(this).apply { background = Ui.rounded(if (i <= step) Ui.ACCENT else Ui.LINE, dp(2).toFloat()) }, lp(0, dp(4), 1f, start = if (i > 0) dp(6) else 0))
        }
        c.addView(dots, lp(MATCH, WRAP, top = dp(8)))
        // Said on every step: nothing here is final.
        c.addView(text(L.t("ob_change_later"), 12f, Ui.MUTED), lp(MATCH, WRAP, top = dp(8)))

        val (title, body) = when (id) {
            "football" -> "ob1_t" to "ob_fb_d"
            "teams" -> "ob_teams_t" to "ob_teams_d"
            "overlay" -> "ob2_t" to "ob2_d"
            "access" -> "ob3_t" to "ob3_d"
            "battery" -> "ob4_t" to "ob4_d"
            "notif" -> "ob5_t" to "ob5_d"
            "restricted" -> "rs_title" to "rs_why"
            "look" -> "ob_look_t" to "ob_look_d"
            "idle" -> "ob_idle_t" to "ob_idle_d"
            "location" -> "ob_loc_t" to "ob_loc_d"
            else -> "ob6_t" to "ob6_d"
        }
        if (id == "overlay" || id == "access") c.addView(phoneArt(), lp(dp(200), dp(170), top = dp(22)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        c.addView(text(L.t(title), 24f, bold = true).apply { setLineSpacing(0f, 1.2f) }, lp(MATCH, WRAP, top = dp(18)))
        c.addView(text(L.t(body), 15f, Ui.MUTED).apply { setLineSpacing(0f, 1.3f) }, lp(MATCH, WRAP, top = dp(10)))

        var action: (() -> Unit)? = null
        var mainButton = true
        when (id) {
            "football" -> {
                c.addView(label(L.t("language")), lp(MATCH, WRAP, top = dp(20)))
                c.addView(segmented(listOf("ar" to "_العربية", "en" to "_English", "system" to "lang_system"), prefs.lang) {
                    prefs.lang = it
                    L.load(this)
                    recreate()
                }, lp(MATCH, WRAP, top = dp(8)))
                // Two big answers instead of a small "skip" link.
                c.addView(button(L.t("fb_yes"), primary = true) {
                    prefs.football = true
                    step++
                    drawOnboarding()
                }, lp(MATCH, dp(58), top = dp(24)))
                c.addView(button(L.t("fb_no")) {
                    prefs.football = false
                    prefs.idleNotch = true
                    step++
                    drawOnboarding()
                }, lp(MATCH, dp(58), top = dp(10)))
                c.addView(text(L.t("fb_no_d"), 12f, Ui.MUTED), lp(MATCH, WRAP, top = dp(8)))
                mainButton = false
            }
            "teams" -> {
                c.addView(label(L.t("ob_teams")), lp(MATCH, WRAP, top = dp(16)))
                teamsEditor(c)
                c.addView(label(L.t("comps")), lp(MATCH, WRAP, top = dp(16)))
                val ck = card(0).apply { setPadding(dp(14), dp(2), dp(14), dp(2)) }
                ck.addView(navRow(L.t("comps_pick"), "${prefs.comps.size}") {
                    page = "comps"
                    draw()
                })
                c.addView(ck, lp(MATCH, WRAP, top = dp(8)))
            }
            "overlay" -> action = { openOverlay() }
            "access" -> {
                // Android 13+ blocks accessibility for apps installed outside the store until the user
                // allows "restricted settings": the step most people can't find (1.8).
                if (restrictedLikely()) c.addView(restrictedGuide(), lp(MATCH, WRAP, top = dp(14)))
                else c.addView(hint(L.t("ob3_h")), lp(MATCH, WRAP, top = dp(14)))
                action = { openAccessibility() }
            }
            "battery" -> batterySteps(c)
            "notif" -> action = {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            "restricted" -> {
                if (restrictedOk()) {
                    c.addView(text("✓ " + L.t("rs_done"), 16f, Ui.ACCENT, bold = true), lp(MATCH, WRAP, top = dp(18)))
                } else {
                    c.addView(restrictedGuide(steps = false), lp(MATCH, WRAP, top = dp(14)))
                }
            }
            "look" -> {
                // On the real notch (it shows a sample while these change).
                c.addView(label(L.t("pill_mode")), lp(MATCH, WRAP, top = dp(16)))
                c.addView(segmented(listOf("auto" to "pill_auto", "yes" to "pill_yes", "no" to "pill_no"), prefs.pillMode) {
                    prefs.pillMode = it
                    LiveService.start(this, LiveService.ACTION_REFRESH)
                    drawOnboarding()
                }, lp(MATCH, WRAP, top = dp(8)))
                c.addView(label(L.t("tpl")), lp(MATCH, WRAP, top = dp(16)))
                c.addView(segmented(listOf("auto" to "tpl_auto", "A" to "_A", "B" to "_B", "C" to "_C", "D" to "_D"), prefs.template) {
                    prefs.template = it
                    LiveService.start(this, LiveService.ACTION_REFRESH)
                    LiveService.start(this, LiveService.ACTION_DEMO, if (prefs.football) "preview" else "idle")
                    drawOnboarding()
                }, lp(MATCH, WRAP, top = dp(8)))
                c.addView(text(L.t("tpl_d"), 11f, Ui.MUTED).apply { setLineSpacing(0f, 1.25f) }, lp(MATCH, WRAP, top = dp(6)))
                val k = card()
                if (prefs.football) {
                    k.addView(slider(L.t("size"), 50, 140, prefs.scale, 100) { prefs.scale = it })
                    k.addView(slider(L.t("width_pct"), 40, 130, prefs.widthPct, 100) { prefs.widthPct = it }, lp(MATCH, WRAP, top = dp(8)))
                    k.addView(slider(L.t("up_down"), -20, 20, prefs.offsetY, 0) { prefs.offsetY = it }, lp(MATCH, WRAP, top = dp(8)))
                } else {
                    k.addView(slider(L.t("idle_scale"), 50, 150, prefs.idleScale, 90, preview = "idle") { prefs.idleScale = it })
                    k.addView(slider(L.t("idle_size"), 50, 150, prefs.idleSize, 100, preview = "idle") { prefs.idleSize = it }, lp(MATCH, WRAP, top = dp(8)))
                    k.addView(slider(L.t("up_down"), -20, 20, prefs.idleOffsetY, 0, preview = "idle") { prefs.idleOffsetY = it }, lp(MATCH, WRAP, top = dp(8)))
                }
                c.addView(k, lp(MATCH, WRAP, top = dp(12)))
                if (!Settings.canDrawOverlays(this)) c.addView(hint(L.t("ob_look_noperm")), lp(MATCH, WRAP, top = dp(12)))
            }
            "idle" -> {
                val k = card(0).apply { setPadding(dp(14), dp(2), dp(14), dp(2)) }
                k.addView(toggleRow(L.t("idle"), L.t("idle_d"), prefs.idleNotch) {
                    prefs.idleNotch = it
                    LiveService.start(this, LiveService.ACTION_REFRESH)
                    drawOnboarding()
                })
                c.addView(k, lp(MATCH, WRAP, top = dp(16)))
                if (prefs.idleNotch) {
                    // What it shows, right here: anyone can drop what they don't use (prayers…).
                    c.addView(label(L.t("idle_content")), lp(MATCH, WRAP, top = dp(16)))
                    val ck = card(0).apply { setPadding(dp(14), dp(2), dp(14), dp(2)) }
                    val keys = listOf("weather", "date", "hijri", "prayer") + if (prefs.football) listOf("match") else emptyList()
                    keys.forEachIndexed { i, key ->
                        if (i > 0) ck.addView(divider())
                        ck.addView(toggleRow(L.t("ic_$key"), null, key in prefs.idleShow) { on ->
                            prefs.idleShow = if (on) prefs.idleShow + key else prefs.idleShow - key
                            LiveService.start(this, LiveService.ACTION_DEMO, "idle")
                        })
                    }
                    c.addView(ck, lp(MATCH, WRAP, top = dp(8)))
                    val sk = card(0).apply { setPadding(dp(14), dp(2), dp(14), dp(2)) }
                    sk.addView(navRow(L.t("slots"), L.f("slots_n", Shortcuts.load(this).count { it != null })) {
                        page = "slots"
                        draw()
                    })
                    c.addView(sk, lp(MATCH, WRAP, top = dp(8)))
                }
            }
            "location" -> {
                locationCard(c)
                if (prefs.locAuto && !Locator.canBg(this)) action = { askLocation() }
            }
            else -> {
                // The summary: each permission with ✓/✗; a tap goes back to its step.
                val k = card(0).apply { setPadding(dp(14), dp(2), dp(14), dp(2)) }
                val ids = stepIds()
                listOf("notif" to "perm_notif", "restricted" to "perm_restricted", "overlay" to "perm_overlay", "access" to "perm_access", "battery" to "perm_battery", "location" to "perm_location")
                    .filter { it.first in ids }.forEachIndexed { i, (sid, key) ->
                        if (i > 0) k.addView(divider())
                        k.addView(navRow(L.t(key), if (grantedId(sid)) "✓" else "✗") {
                            step = ids.indexOf(sid)
                            drawOnboarding()
                        })
                    }
                c.addView(k, lp(MATCH, WRAP, top = dp(16)))
                if (ids.any { !grantedId(it) }) c.addView(hint(L.t("perm_later")), lp(MATCH, WRAP, top = dp(12)))
            }
        }
        if (id == "overlay" || id == "access") c.addView(hint(L.t("ob_hint")), lp(MATCH, WRAP, top = dp(14)))

        c.addView(View(this), lp(MATCH, 0, 1f))
        val done = granted(step)
        val last = step == steps - 1
        val act = action
        if (!mainButton) {
            // The step's own buttons move on.
        } else if (act != null && !done) {
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
        // From the look step on, the rest can wait (all of it is in Settings).
        if (wizard == null && !last && step >= stepIds().indexOf("look")) {
            c.addView(link(L.t("finish_later")) { finishOnboarding() }.apply {
                setTextColor(Ui.MUTED)
                gravity = Gravity.CENTER
            }, lp(MATCH, WRAP, top = dp(12)))
        }
    }

    /** Battery: no limits, plus Xiaomi's autostart and pop-up pages. */
    private fun batterySteps(c: LinearLayout) {
        val k = card(0).apply { setPadding(dp(14), dp(2), dp(14), dp(2)) }
        k.addView(navRow(L.t("battery_step"), if (grantedId("battery")) L.t("granted") else "") {
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

    /* ----- 1.9: the place (automatic, or chosen) ----- */

    /** Location permission: while in use first, then "Allow all the time" (its own page on Android 11+). */
    private fun askLocation() {
        if (!Locator.canFg(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
        } else if (!Locator.canBg(this) && Build.VERSION.SDK_INT >= 29) {
            AlertDialog.Builder(this)
                .setTitle(L.t("loc_bg_t"))
                .setMessage(L.t("loc_bg_d"))
                .setPositiveButton(L.t("open_setting")) { _, _ -> requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_LOC_BG) }
                .setNegativeButton(L.t("cancel"), null)
                .show()
        }
    }

    private fun locationCard(c: LinearLayout) {
        val k = card(0).apply { setPadding(dp(14), dp(2), dp(14), dp(2)) }
        val auto = prefs.autoCity
        val sub = when {
            !prefs.locAuto || !Locator.canFg(this) -> L.t("loc_off")
            !Locator.canBg(this) -> L.t("loc_need_bg")
            auto != null && auto.name.isNotEmpty() -> L.f("loc_here", auto.name)
            else -> L.t("loc_finding")
        }
        // Shown on only once there's a permission: turning it on asks straight away (1.9.1).
        k.addView(toggleRow(L.t("loc_auto"), sub, prefs.locAuto && Locator.canFg(this)) {
            prefs.locAuto = it
            if (it) askLocation()
            LiveService.start(this, LiveService.ACTION_REFRESH)
            if (onboarding) drawOnboarding() else draw()
        })
        if (prefs.locAuto && Locator.canFg(this) && !Locator.canBg(this)) {
            k.addView(divider())
            k.addView(navRow(L.t("loc_allow_always"), "") { askLocation() })
        }
        if (prefs.locAuto && Locator.canFg(this)) {
            k.addView(divider())
            k.addView(navRow(L.t("loc_now"), "") {
                Locator.refresh(this, prefs)
                LiveService.start(this, LiveService.ACTION_REFRESH)
                toast(L.t("loc_finding"))
                main.postDelayed({ if (onboarding) drawOnboarding() else draw() }, 2500)
            })
        }
        k.addView(divider())
        k.addView(navRow(L.t(if (prefs.locAuto) "loc_fallback" else "city"), prefs.city.name) {
            backTo = page
            searchQuery = ""
            page = "city"
            draw()
        })
        c.addView(k, lp(MATCH, WRAP, top = dp(16)))
        if (prefs.locAuto) c.addView(text(L.t("loc_note"), 11f, Ui.MUTED).apply { setLineSpacing(0f, 1.25f) }, lp(MATCH, WRAP, top = dp(6)))
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_LOC && Locator.canFg(this)) {
            Locator.refresh(this, prefs)
            LiveService.start(this, LiveService.ACTION_REFRESH)
            // Straight on to "all the time".
            if (!Locator.canBg(this)) askLocation()
        }
        if (code == REQ_LOC_BG) LiveService.start(this, LiveService.ACTION_REFRESH)
        if (onboarding && stepId() == "notif" && code == 1) {
            step++
        }
        if (onboarding) drawOnboarding() else draw()
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

    /** "Display over other apps", with fallbacks for ROMs that refuse the direct page. */
    private fun openOverlay() {
        for (i in listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        )) {
            try {
                startActivity(i)
                return
            } catch (e: Exception) {
                // Next one.
            }
        }
        appDetails()
    }

    /**
     * Android 13+ marks sideloaded apps "restricted": accessibility and notification access stay grey
     * until App info → ⋮ → Allow restricted settings. Not for store installs, or once it's allowed.
     */
    /** Android 13+ and installed outside the store: the restricted-settings step applies. */
    private fun restrictedNeeded(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            packageManager.getInstallSourceInfo(packageName).installingPackageName != "com.android.vending"
        } catch (e: Exception) {
            true
        }
    }

    /** Restricted settings allowed (or not needed, or accessibility already on). */
    private fun restrictedOk(): Boolean {
        if (!restrictedNeeded() || TapService.instance != null) return true
        return try {
            val ops = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
            ops.unsafeCheckOpNoThrow("android:access_restricted_settings", applicationInfo.uid, packageName) == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private fun restrictedLikely(): Boolean {
        if (Build.VERSION.SDK_INT < 33 || TapService.instance != null) return false
        try {
            if (packageManager.getInstallSourceInfo(packageName).installingPackageName == "com.android.vending") return false
        } catch (e: Exception) {
            // Unknown: keep checking.
        }
        return try {
            val ops = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
            ops.unsafeCheckOpNoThrow("android:access_restricted_settings", applicationInfo.uid, packageName) != android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            true
        }
    }

    /** Three numbered steps with a button each: try once, allow restricted settings, turn it on. */
    private fun restrictedGuide(steps: Boolean = true): View {
        val k = card(14).apply { background = Ui.rounded(Ui.CARD, dp(16).toFloat(), Ui.AMBER, dp(1)) }
        if (steps) {
            k.addView(text(L.t("rs_title"), 15f, Ui.AMBER, bold = true))
            k.addView(text(L.t("rs_why"), 12f, Ui.MUTED).apply { setLineSpacing(0f, 1.25f) }, lp(MATCH, WRAP, top = dp(4)))
        }
        val b = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        val where = when {
            b.contains("honor") || b.contains("huawei") -> "rs_where_honor"
            b.contains("samsung") -> "rs_where_samsung"
            b.contains("xiaomi") || b.contains("redmi") || b.contains("poco") -> "rs_where_xiaomi"
            else -> "rs_where"
        }
        listOf(
            Triple("rs_1", "rs_1b") { openAccessibility() },
            Triple("rs_2", "rs_2b") { appDetails() },
            Triple("rs_3", "rs_3b") { openAccessibility() },
        ).forEachIndexed { i, (t, btn, go) ->
            k.addView(text(L.t(t), 13f, bold = true), lp(MATCH, WRAP, top = dp(12)))
            if (i == 1) k.addView(text(L.t(where), 12f, Ui.MUTED).apply { setLineSpacing(0f, 1.25f) }, lp(MATCH, WRAP, top = dp(2)))
            k.addView(button(L.t(btn), primary = i == 1) { go() }, lp(MATCH, WRAP, top = dp(6)))
        }
        return k
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
