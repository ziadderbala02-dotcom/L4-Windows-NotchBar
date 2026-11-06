package com.l4.notchlive

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.LinearLayout
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
import com.l4.notchlive.Ui.dp
import com.l4.notchlive.Ui.lp
import com.l4.notchlive.Ui.row
import com.l4.notchlive.Ui.text
import java.util.concurrent.Executors

/** Setup: permissions, favourite teams, position of the notch, a test button. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var perms: LinearLayout
    private lateinit var teamList: LinearLayout
    private lateinit var results: LinearLayout
    private lateinit var update: LinearLayout
    private lateinit var liveList: LinearLayout
    private lateinit var liveFilter: EditText
    private lateinit var report: TextView
    private var live: List<Match> = emptyList()
    private var downloadId = -1L
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        val root = column(16).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Ui.BG)
        }

        // Header + master switch.
        val head = row()
        head.addView(text("Notch Live", 24f, bold = true), lp(0, WRAP, 1f))
        head.addView(Switch(this).apply {
            isChecked = prefs.enabled
            setOnCheckedChangeListener { _, on ->
                prefs.enabled = on
                if (on) LiveService.start(this@MainActivity) else stopService(Intent(this@MainActivity, LiveService::class.java))
            }
        })
        root.addView(head)
        root.addView(text("سكور ماتشات فرقك حوالين الكاميرا، وقت الماتش بس.", 13f, Ui.MUTED), lp(MATCH, WRAP, top = dp(4)))

        // New version banner (hidden until there is one).
        update = card().apply { visibility = View.GONE }
        root.addView(update, lp(MATCH, WRAP, top = dp(16)))

        // Permissions.
        perms = card()
        root.addView(perms, lp(MATCH, WRAP, top = dp(16)))

        // Teams.
        val teams = card()
        teams.addView(text("فرقك", 16f, bold = true))
        teamList = column()
        teams.addView(teamList, lp(MATCH, WRAP, top = dp(8)))
        val search = row()
        val box = EditText(this).apply {
            hint = "دوّر على فريق…"
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = Ui.rounded(Ui.LINE, dp(10).toFloat())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val go = { find(box.text.toString().trim()) }
        box.setOnEditorActionListener { _, _, _ -> go(); true }
        search.addView(box, lp(0, WRAP, 1f))
        search.addView(button("دوّر") { go() }, lp(WRAP, WRAP, start = dp(8)))
        teams.addView(search, lp(MATCH, WRAP, top = dp(10)))
        results = column()
        teams.addView(results, lp(MATCH, WRAP, top = dp(6)))
        root.addView(teams, lp(MATCH, WRAP, top = dp(12)))

        // Live now: pin any match to the notch.
        val now = card()
        val nowHead = row()
        nowHead.addView(text("ماتشات دلوقتي", 16f, bold = true), lp(0, WRAP, 1f))
        nowHead.addView(button("حدّث") { loadLive() })
        now.addView(nowHead)
        now.addView(text("ثبّت 📌 أي ماتش عشان يظهر في النوتش حتى لو مش لفرقك. بيتشال لوحده لما يخلص.", 12f, Ui.MUTED), lp(MATCH, WRAP, top = dp(2)))
        liveFilter = EditText(this).apply {
            hint = "فلتر: فريق أو دوري…"
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT
            background = Ui.rounded(Ui.LINE, dp(10).toFloat())
            setPadding(dp(12), dp(6), dp(12), dp(6))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) = drawLive()
            })
        }
        now.addView(liveFilter, lp(MATCH, WRAP, top = dp(8)))
        liveList = column()
        now.addView(liveList, lp(MATCH, WRAP, top = dp(6)))
        root.addView(now, lp(MATCH, WRAP, top = dp(12)))

        // Position and size.
        val pos = card()
        pos.addView(text("مكان النوتش", 16f, bold = true))
        pos.addView(text("جرّب، وظبط لحد ما تلف الكاميرا بالظبط.", 12f, Ui.MUTED), lp(MATCH, WRAP, top = dp(2)))
        pos.addView(slider("لفوق / لتحت", -20, 20, prefs.offsetY) { prefs.offsetY = it }, lp(MATCH, WRAP, top = dp(10)))
        pos.addView(slider("الحجم", 80, 140, prefs.scale) { prefs.scale = it }, lp(MATCH, WRAP, top = dp(6)))
        val demo = row()
        demo.addView(button("جرّب جول ⚽", primary = true) { LiveService.start(this, LiveService.ACTION_DEMO, "goal") }, lp(0, WRAP, 1f))
        demo.addView(button("طرد 🟥") { LiveService.start(this, LiveService.ACTION_DEMO, "red") }, lp(0, WRAP, 1f, start = dp(6)))
        demo.addView(button("صفارة") { LiveService.start(this, LiveService.ACTION_DEMO, "whistle") }, lp(0, WRAP, 1f, start = dp(6)))
        pos.addView(demo, lp(MATCH, WRAP, top = dp(12)))
        pos.addView(text("في التجربة: دوس على النوتش تكبر، ودوس تاني تفتح 365. اسحبها يمين تستخبى، وشمال ترجع.", 11f, Ui.MUTED), lp(MATCH, WRAP, top = dp(6)))
        root.addView(pos, lp(MATCH, WRAP, top = dp(12)))

        // Options.
        val opts = card()
        opts.addView(toggle("صوت الجول والكروت", prefs.sound) { prefs.sound = it })
        opts.addView(toggle("اهتزاز مع الجون والطرد", prefs.vibrate) { prefs.vibrate = it }, lp(MATCH, WRAP, top = dp(8)))
        opts.addView(
            toggle("تظهر فوق الألعاب والفيديو (full screen)", prefs.overFullscreen) {
                prefs.overFullscreen = it
                LiveService.start(this, LiveService.ACTION_REFRESH)
            },
            lp(MATCH, WRAP, top = dp(8)),
        )
        opts.addView(
            toggle("اخفيها لما يكون في صوت شغال (أغاني أو فيديو)", prefs.hideOnAudio) { prefs.hideOnAudio = it },
            lp(MATCH, WRAP, top = dp(8)),
        )
        root.addView(opts, lp(MATCH, WRAP, top = dp(12)))

        report = text("", 11f, Ui.MUTED)
        root.addView(report, lp(MATCH, WRAP, top = dp(12)))

        root.addView(
            text("الإصدار ${packageManager.getPackageInfo(packageName, 0).versionName} • البيانات من 365Scores", 11f, Ui.MUTED).apply {
                gravity = Gravity.CENTER
            },
            lp(MATCH, WRAP, top = dp(16)),
        )

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(root)
        })
        drawTeams()

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        drawPerms()
        if (prefs.enabled) LiveService.start(this, LiveService.ACTION_REFRESH)
        report.text = prefs.report.ifBlank { "تقرير البطارية والداتا هيظهر هنا بعد أول ماتش." }
        loadLive()
        checkUpdate()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(downloaded)
        } catch (e: Exception) {
            // Not registered.
        }
        super.onDestroy()
    }

    /* ---------- live now + pins ---------- */

    private fun loadLive() {
        liveList.removeAllViews()
        liveList.addView(text("بيحمّل…", 12f, Ui.MUTED))
        io.execute {
            val l = try {
                Api.liveNow()
            } catch (e: Exception) {
                null
            }
            main.post {
                if (isFinishing) return@post
                if (l == null) {
                    liveList.removeAllViews()
                    liveList.addView(text("مفيش نت أو 365Scores مش بيرد.", 12f, 0xFFFCA5A5.toInt()))
                    return@post
                }
                live = l
                drawLive()
            }
        }
    }

    private fun drawLive() {
        liveList.removeAllViews()
        val q = liveFilter.text.toString().trim()
        val pins = prefs.pinned
        val list = live
            .filter { q.isEmpty() || it.home.name.contains(q, true) || it.away.name.contains(q, true) || it.comp.contains(q, true) }
            .sortedWith(compareBy({ it.id !in pins }, { it.comp }))
        if (live.isEmpty()) liveList.addView(text("مفيش ماتشات شغالة دلوقتي.", 12f, Ui.MUTED))
        var lastComp = ""
        for (m in list.take(60)) {
            if (m.comp != lastComp) {
                lastComp = m.comp
                liveList.addView(text(m.comp, 11f, Ui.ACCENT), lp(MATCH, WRAP, top = dp(10)))
            }
            val r = row()
            val txt = column()
            txt.addView(text("${m.home.short}  ${m.home.score} - ${m.away.score}  ${m.away.short}", 13f).apply { maxLines = 1 })
            txt.addView(text(m.clock, 11f, Ui.MUTED))
            r.addView(txt, lp(0, WRAP, 1f))
            val pinned = m.id in prefs.pinned
            r.addView(button(if (pinned) "📌 مثبّت" else "📌 ثبّت", primary = pinned) {
                prefs.pinned = if (m.id in prefs.pinned) prefs.pinned - m.id else prefs.pinned + m.id
                drawLive()
                LiveService.start(this, LiveService.ACTION_REFRESH)
            })
            liveList.addView(r, lp(MATCH, WRAP, top = dp(4)))
        }
        if (list.size > 60) liveList.addView(text("و${list.size - 60} كمان… استخدم الفلتر.", 11f, Ui.MUTED), lp(MATCH, WRAP, top = dp(6)))
    }

    /* ---------- new version ---------- */

    private fun checkUpdate() {
        io.execute {
            val r = try {
                Updater.latest()
            } catch (e: Exception) {
                null
            }
            main.post {
                if (isFinishing) return@post
                if (r == null || !Updater.newer(r.version, Updater.current(this))) {
                    update.visibility = View.GONE
                    return@post
                }
                update.removeAllViews()
                update.visibility = View.VISIBLE
                update.addView(text("نسخة جديدة: ${r.version}", 15f, Ui.ACCENT, bold = true))
                update.addView(text("هتتنزل وتتسطّب فوق النسخة دي. أول مرة أندرويد هيطلب تسمح للتطبيق يسطّب.", 12f, Ui.MUTED), lp(MATCH, WRAP, top = dp(2)))
                update.addView(button("حمّل وسطّب", primary = true) { download(r) }, lp(MATCH, WRAP, top = dp(8)))
            }
        }
    }

    private val downloaded = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) {
            val id = i.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != downloadId) return
            val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
            val uri = dm.getUriForDownloadedFile(id) ?: return
            install(uri)
        }
    }

    private fun download(r: Updater.Release) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
        val req = android.app.DownloadManager.Request(Uri.parse(r.apkUrl))
            .setTitle("Notch Live ${r.version}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, android.os.Environment.DIRECTORY_DOWNLOADS, "NotchLive_${r.version}.apk")
        val f = IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloaded, f, RECEIVER_EXPORTED) else registerReceiver(downloaded, f)
        } catch (e: Exception) {
            // Already listening.
        }
        downloadId = dm.enqueue(req)
        update.addView(text("بيتحمّل… هيفتح التسطيب لوحده لما يخلص.", 12f, Ui.MUTED), lp(MATCH, WRAP, top = dp(6)))
    }

    private fun install(uri: Uri) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            // Open it from the download notification instead.
        }
    }

    /* ---------- permissions ---------- */

    private fun drawPerms() {
        perms.removeAllViews()
        perms.addView(text("الأذونات", 16f, bold = true))
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val overlay = Settings.canDrawOverlays(this)
        val tap = TapService.instance != null
        permRow("الظهور فوق التطبيقات", "لازم عشان النوتش تظهر", overlay || tap) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            permRow("الإشعارات", "للجون لما النوتش مستخبية", checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        permRow("البطارية: بدون قيود", "عشان شاومي ميقفلش التطبيق", pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                appDetails()
            }
        }
        permRow("التشغيل التلقائي (شاومي)", "فعّل Notch Live في القايمة", null) {
            try {
                startActivity(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
            } catch (e: Exception) {
                appDetails()
            }
        }
        permRow("صلاحيات شاومي الإضافية", "فعّل \"Display pop-up windows while running in background\" عشان الضغطة تفتح الماتش", null) {
            try {
                startActivity(Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", packageName))
            } catch (e: Exception) {
                appDetails()
            }
        }
        permRow("الضغط والسحب على النوتش", "إمكانية الوصول ← التطبيقات المنزّلة ← Notch Live. من غيرها شريط الحالة بياكل الضغطات. لو قال \"إعداد مقيد\": معلومات التطبيق ← ⋮ ← اسمح بالإعدادات المقيدة", tap) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun appDetails() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    /** One permission line: name, hint, ✓ when granted (null = can't tell), and a button. */
    private fun permRow(name: String, hint: String, ok: Boolean?, open: () -> Unit) {
        val r = row()
        val texts = column()
        texts.addView(text((if (ok == true) "✓  " else if (ok == false) "✗  " else "•  ") + name, 14f, if (ok == false) 0xFFFCA5A5.toInt() else Ui.TEXT))
        texts.addView(text(hint, 11f, Ui.MUTED))
        r.addView(texts, lp(0, WRAP, 1f))
        if (ok != true) r.addView(button("افتح") { open() })
        perms.addView(r, lp(MATCH, WRAP, top = dp(10)))
    }

    /* ---------- teams ---------- */

    private fun drawTeams() {
        teamList.removeAllViews()
        for (t in prefs.teams) {
            val r = row()
            r.addView(crest(t.logo, 26))
            r.addView(text(t.name, 14f), lp(0, WRAP, 1f, start = dp(10)))
            r.addView(button("شيل") {
                prefs.teams = prefs.teams.filter { it.id != t.id }
                drawTeams()
                LiveService.start(this, LiveService.ACTION_REFRESH)
            })
            teamList.addView(r, lp(MATCH, WRAP, top = dp(6)))
        }
        if (prefs.teams.isEmpty()) teamList.addView(text("مفيش فرق. دوّر وضيف.", 13f, Ui.MUTED))
    }

    private fun find(q: String) {
        if (q.length < 2) return
        results.removeAllViews()
        results.addView(text("بدوّر…", 12f, Ui.MUTED))
        io.execute {
            val found = try {
                Api.search(q)
            } catch (e: Exception) {
                null
            }
            main.post { showResults(found) }
        }
    }

    private fun showResults(found: List<TeamOption>?) {
        results.removeAllViews()
        if (found == null) {
            results.addView(text("مفيش نت أو 365Scores مش بيرد.", 12f, 0xFFFCA5A5.toInt()))
            return
        }
        if (found.isEmpty()) results.addView(text("ملقيتش فرق بالاسم ده.", 12f, Ui.MUTED))
        for (t in found) {
            val r = row()
            r.addView(crest(t.logo, 24))
            r.addView(text(t.name, 13f), lp(0, WRAP, 1f, start = dp(10)))
            val have = prefs.teams.any { it.id == t.id }
            r.addView(button(if (have) "متضاف" else "ضيف", primary = !have) {
                if (prefs.teams.none { it.id == t.id }) {
                    prefs.teams = prefs.teams + t
                    drawTeams()
                    showResults(found)
                    LiveService.start(this, LiveService.ACTION_REFRESH)
                }
            })
            results.addView(r, lp(MATCH, WRAP, top = dp(6)))
        }
    }

    /* ---------- controls ---------- */

    private fun slider(label: String, lo: Int, hi: Int, value: Int, save: (Int) -> Unit): View {
        val box = column()
        val title = text("$label: $value", 13f, Ui.MUTED)
        box.addView(title)
        box.addView(SeekBar(this).apply {
            max = hi - lo
            progress = value - lo
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    title.text = "$label: ${p + lo}"
                }

                override fun onStartTrackingTouch(s: SeekBar) {}

                override fun onStopTrackingTouch(s: SeekBar) {
                    save(s.progress + lo)
                    // Show the change right away with the test match.
                    LiveService.start(this@MainActivity, LiveService.ACTION_REFRESH)
                    LiveService.start(this@MainActivity, LiveService.ACTION_DEMO, "none")
                }
            })
        }, lp(MATCH, WRAP, top = dp(4)))
        return box
    }

    private fun toggle(label: String, on: Boolean, save: (Boolean) -> Unit): View {
        val r = row()
        val t: TextView = text(label, 14f)
        r.addView(t, lp(0, WRAP, 1f))
        r.addView(Switch(this).apply {
            isChecked = on
            setOnCheckedChangeListener { _, v -> save(v) }
        })
        return r
    }
}
