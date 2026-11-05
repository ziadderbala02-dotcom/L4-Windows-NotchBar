package com.l4.notchlive

import android.Manifest
import android.app.Activity
import android.content.ComponentName
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

        // Position and size.
        val pos = card()
        pos.addView(text("مكان النوتش", 16f, bold = true))
        pos.addView(text("جرّب، وظبط لحد ما تلف الكاميرا بالظبط.", 12f, Ui.MUTED), lp(MATCH, WRAP, top = dp(2)))
        pos.addView(slider("لفوق / لتحت", -20, 20, prefs.offsetY) { prefs.offsetY = it }, lp(MATCH, WRAP, top = dp(10)))
        pos.addView(slider("الحجم", 80, 140, prefs.scale) { prefs.scale = it }, lp(MATCH, WRAP, top = dp(6)))
        val demo = row()
        demo.addView(button("جرّب جول ⚽", primary = true) { LiveService.start(this, LiveService.ACTION_DEMO, "goal") }, lp(0, WRAP, 1f))
        demo.addView(button("جرّب طرد 🟥") { LiveService.start(this, LiveService.ACTION_DEMO, "red") }, lp(0, WRAP, 1f, start = dp(8)))
        pos.addView(demo, lp(MATCH, WRAP, top = dp(12)))
        root.addView(pos, lp(MATCH, WRAP, top = dp(12)))

        // Options.
        val opts = card()
        opts.addView(toggle("صوت الجول والكروت", prefs.sound) { prefs.sound = it })
        opts.addView(
            toggle("اخفيها لما يكون في صوت شغال (أغاني أو فيديو)", prefs.hideOnAudio) { prefs.hideOnAudio = it },
            lp(MATCH, WRAP, top = dp(8)),
        )
        root.addView(opts, lp(MATCH, WRAP, top = dp(12)))

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
        permRow("الضغط على النوتش (اختياري)", "إمكانية الوصول ← Notch Live. بيخلي الضغطة تفتح الماتش", tap) {
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
