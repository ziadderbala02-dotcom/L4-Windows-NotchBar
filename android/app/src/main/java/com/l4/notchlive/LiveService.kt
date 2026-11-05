package com.l4.notchlive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

/*
 * Watches the favourite teams' matches (same rules as the Windows notch):
 *   - polls 365Scores every 12 s while a match is live or about to start, else every few minutes
 *   - goal / goal ruled out / red card / VAR / penalty / kickoff / second half → a burst + sound
 *   - several live matches take turns every 10 s; a finished one stays 30 s with "انتهت"
 *   - the notch only exists while a match is on
 * Runs as a foreground service with a quiet ongoing notification, so MIUI doesn't kill it.
 */
class LiveService : Service() {
    companion object {
        @Volatile
        var instance: LiveService? = null

        private const val CH_LIVE = "live"
        private const val CH_ALERT = "alerts"
        private const val NOTIF_ID = 1
        const val ACTION_REFRESH = "refresh"
        const val ACTION_DEMO = "demo"
        const val ACTION_STOP = "stop"

        private const val FAST_MS = 12_000L
        private const val SLOW_MS = 5 * 60_000L
        private const val RETRY_MS = 60_000L
        private const val FINAL_MS = 30_000L
        private const val ROTATE_MS = 10_000L
        private const val BURST_MS = 6_500L

        fun start(ctx: Context, action: String? = null, extra: String? = null) {
            val i = Intent(ctx, LiveService::class.java)
            if (action != null) i.action = action
            if (extra != null) i.putExtra("kind", extra)
            try {
                ctx.startForegroundService(i)
            } catch (e: Exception) {
                // Not allowed from the background right now; the app or a reboot starts it.
            }
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var island: Island
    private lateinit var sounds: Sounds
    private lateinit var audio: AudioManager
    private lateinit var nm: NotificationManager
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadScheduledExecutor()

    @Volatile
    private var gen = 0

    // Main thread only from here on.
    private var matches: List<Match> = emptyList()
    private val shown = HashMap<Int, Match>()
    private val seen = HashMap<Int, MutableSet<String>>()
    private val endedAt = HashMap<Int, Long>()
    private val stoppage = HashMap<Int, Pair<Int, Long>>()
    private val queue = ArrayDeque<Burst>()
    private var current: Burst? = null
    private var currentUntil = 0L
    private var nextBurstAt = 0L
    private var demo: Match? = null
    private var demoUntil = 0L
    private var lastNotif = ""

    private val tick = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 1000)
        }
    }

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refresh()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_LIVE, "متابعة الماتشات", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "أهداف وأحداث", NotificationManager.IMPORTANCE_HIGH).apply {
                // The app plays its own sounds.
                setSound(null, null)
            },
        )
        val n = ongoing("Notch Live", "مستني ماتشات فرقك")
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
        sounds = Sounds(this)
        island = Island(this, prefs) { id -> openMatch(id) }
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
        refresh()
        main.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!prefs.enabled || intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_REFRESH -> {
                island.reattach()
                refresh()
            }
            ACTION_DEMO -> startDemo(intent.getStringExtra("kind") ?: "goal")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        gen++
        main.removeCallbacksAndMessages(null)
        net.shutdownNow()
        island.hide()
        sounds.release()
        try {
            unregisterReceiver(screenOn)
        } catch (e: Exception) {
            // Not registered.
        }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        island.reattach()
        render()
    }

    /** The accessibility service came or went: move the window to the new host. */
    fun hostChanged() = main.post {
        island.reattach()
        render()
    }

    /* ---------- polling ---------- */

    fun refresh() {
        val g = ++gen
        net.execute { poll(g) }
    }

    private fun poll(g: Int) {
        if (g != gen) return
        var next = SLOW_MS
        try {
            val ids = prefs.teams.map { it.id }
            var list = Api.teamGames(ids)
            val live = list.filter { it.state == Api.LIVE }
            if (live.isNotEmpty()) {
                // The list has no events: fetch each live game for scorers and cards.
                val details = live.mapNotNull { m ->
                    try {
                        Api.game(m.id).copy(comp = m.comp)
                    } catch (e: Exception) {
                        null
                    }
                }.associateBy { it.id }
                list = list.map { details[it.id] ?: it }
            }
            val now = System.currentTimeMillis()
            val hot = list.any {
                it.state == Api.LIVE || (it.state == Api.PRE && it.kickoff - now < 10 * 60_000 && now - it.kickoff < 3 * 3600_000)
            }
            next = if (hot) FAST_MS else {
                val k = list.filter { it.state == Api.PRE && it.kickoff > now }.minOfOrNull { it.kickoff }
                if (k != null) (k - 10 * 60_000 - now).coerceIn(30_000L, SLOW_MS) else SLOW_MS
            }
            main.post { onData(list) }
        } catch (e: Exception) {
            next = RETRY_MS
        }
        if (g == gen) net.schedule({ poll(g) }, next, TimeUnit.MILLISECONDS)
    }

    /* ---------- spotting what happened ---------- */

    private fun onData(list: List<Match>) {
        val now = System.currentTimeMillis()
        for (m in list) {
            val before = shown[m.id]
            if (before != null) {
                if (before.state == Api.LIVE && m.state == Api.POST) endedAt[m.id] = now
                if (before.state == Api.PRE && m.state == Api.LIVE) {
                    push(Burst("notice", "الماتش بدأ", "${m.home.short} × ${m.away.short}", true, Color.WHITE, m.id))
                }
                for (home in listOf(true, false)) {
                    val diff = m.side(home).score - before.side(home).score
                    if (diff > 0 && m.state != Api.PRE) goal(m, home)
                    else if (diff < 0) push(Burst("var", "الجون اتلغى", m.side(home).short, home, 0xFFF87171.toInt(), m.id))
                }
                if (before.phase == "break" && before.clock == "بين الشوطين" && m.state == Api.LIVE && m.phase == "play") {
                    push(Burst("notice", "بدأ الشوط التاني", "${m.home.short} × ${m.away.short}", true, Color.WHITE, m.id))
                }
            }
            spotIncidents(m)
            trackStoppage(m)
        }
        shown.clear()
        for (m in list) shown[m.id] = m
        matches = list
        render()
    }

    private fun goal(m: Match, home: Boolean) {
        val last = m.events.lastOrNull { it.kind == "goal" && it.home == home }
        val b = Burst("goal", "جووووول ⚽", scorerLine(m, home, last), home, m.side(home).color, m.id)
        // Goals jump the queue.
        queue.addFirst(b)
        if (current != null && current?.kind != "goal") {
            current = null
            nextBurstAt = 0
        }
        if (last == null) {
            // The scorer isn't in yet: ask the game itself once more.
            net.execute {
                try {
                    val g = Api.game(m.id)
                    val ev = g.events.lastOrNull { it.kind == "goal" && it.home == home }
                    if (ev != null) main.post { b.sub = scorerLine(g, home, ev) }
                } catch (e: Exception) {
                    // Keep the team name.
                }
            }
        }
    }

    private fun scorerLine(m: Match, home: Boolean, e: Ev?): String {
        val team = m.side(home).short
        if (e == null || e.player == null) return team
        val tag = when {
            e.own -> " (في مرماه)"
            e.pen -> " (ضربة جزاء)"
            else -> ""
        }
        return listOf(e.player + tag, e.minute).filter { it.isNotBlank() }.joinToString(" • ")
    }

    private val cancelled = Regex("ملغ|إلغاء|الغاء|لا يوجد|لا ركلة|cancel|disallow|overturn|no penalty|no goal|rescind", RegexOption.IGNORE_CASE)
    private val missed = Regex("ضائع|أهدر|اهدر|تصد|miss|saved", RegexOption.IGNORE_CASE)

    private fun spotIncidents(m: Match) {
        if (m.state != Api.LIVE) return
        val ids = m.events.map { it.id }
        val known = seen[m.id]
        if (ids.isEmpty()) {
            // No events yet: only trust that in the first minutes (otherwise the detail failed).
            if (known == null && (m.clock.filter { it.isDigit() }.take(3).toIntOrNull() ?: 99) <= 3) seen[m.id] = HashSet()
            return
        }
        if (known == null) {
            seen[m.id] = ids.toHashSet()
            return
        }
        for (e in m.events) {
            if (!known.add(e.id)) continue
            val who = listOfNotNull(e.player, e.minute.ifBlank { null }).joinToString(" • ").ifBlank { m.side(e.home).short }
            when (e.kind) {
                "red" -> push(Burst("red", "🟥 طرد", who, e.home, 0xFFF87171.toInt(), m.id))
                "var" -> push(Burst("var", e.label.ifBlank { "قرار الفار" }, who, e.home, if (cancelled.containsMatchIn(e.label)) 0xFFF87171.toInt() else 0xFF60A5FA.toInt(), m.id))
                "pen" -> push(Burst("pen", e.label.ifBlank { "ضربة جزاء" }, who, e.home, if (missed.containsMatchIn(e.label)) 0xFFF87171.toInt() else 0xFFFBBF24.toInt(), m.id))
            }
        }
    }

    private fun push(b: Burst) {
        // The provider can send a VAR event and the lowered score together: show it once.
        if (b.kind == "var" && (listOfNotNull(current) + queue).any { it.kind == "var" && it.matchId == b.matchId }) return
        queue.addLast(b)
    }

    /* ---------- the clock in added time ---------- */

    private val ends = setOf(45, 90, 105, 120)
    private val plainMinute = Regex("^(\\d+)'?$")
    private val plus = Regex("\\+\\s*(\\d+)")

    /** 365Scores freezes at 45' / 90' during added time; from then on we count ourselves. */
    private fun trackStoppage(m: Match) {
        val n = if (m.state == Api.LIVE && m.phase == "play") plainMinute.find(m.clock.trim())?.groupValues?.get(1)?.toIntOrNull() else null
        if (n == null || n !in ends) {
            stoppage.remove(m.id)
            return
        }
        val cur = stoppage[m.id]
        if (cur == null || cur.first != n) stoppage[m.id] = n to System.currentTimeMillis()
    }

    private fun clockText(m: Match, now: Long): String {
        val st = stoppage[m.id]
        if (st == null || m.state != Api.LIVE || m.phase != "play") return m.clock
        val feed = plus.find(m.clock)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val extra = max(feed, ((now - st.second) / 60_000).toInt())
        return if (extra > 0) "${st.first}+$extra'" else "${st.first}'"
    }

    /* ---------- what the notch shows ---------- */

    private fun featured(list: List<Match>, now: Long): Match? {
        val live = list.filter { it.state == Api.LIVE || (it.state == Api.POST && now - (endedAt[it.id] ?: 0L) < FINAL_MS) }
        if (live.isEmpty()) return null
        current?.let { b -> live.firstOrNull { it.id == b.matchId }?.let { return it } }
        live.firstOrNull { it.phase == "var" }?.let { return it }
        val running = live.filter { it.state == Api.LIVE }.sortedWith(compareBy({ it.kickoff }, { it.id }))
        if (running.isNotEmpty()) return running[((now / ROTATE_MS) % running.size).toInt()]
        return live.maxByOrNull { it.kickoff }
    }

    private fun inCall(): Boolean = audio.mode == AudioManager.MODE_IN_CALL ||
        audio.mode == AudioManager.MODE_IN_COMMUNICATION ||
        audio.mode == AudioManager.MODE_RINGTONE

    private fun render() {
        val now = System.currentTimeMillis()
        if (current != null && now >= currentUntil) {
            current = null
            nextBurstAt = now + 600
        }
        val list = matches + listOfNotNull(demo?.takeIf { now < demoUntil })
        if (current == null && queue.isNotEmpty() && now >= nextBurstAt) {
            val b = queue.removeFirst()
            // Only if its match is still around (a burst for a match that ended long ago is dropped).
            if (list.any { it.id == b.matchId }) {
                current = b
                currentUntil = now + BURST_MS
                announce(b, list.first { it.id == b.matchId })
            }
        }
        val m = featured(list, now)
        val hidden = !prefs.enabled || inCall() || !island.portrait() || (prefs.hideOnAudio && audio.isMusicActive)
        if (m == null || hidden) island.update(null, "", null)
        else island.update(m, clockText(m, now), current?.takeIf { it.matchId == m.id })
        updateOngoing(m, now)
    }

    private fun announce(b: Burst, m: Match) {
        if (prefs.sound && !inCall()) sounds.play(b.kind)
        // The notch can't show it (no permission, full screen, a call): a normal notification instead.
        if (!island.canShow() || !island.showing || inCall() || !island.portrait()) {
            if (b.kind == "notice") return
            val n = Notification.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_ball)
                .setContentTitle("${b.title}  ${m.home.short} ${m.home.score} - ${m.away.score} ${m.away.short}")
                .setContentText(b.sub)
                .setAutoCancel(true)
                .setContentIntent(matchIntent(m.id))
                .build()
            try {
                nm.notify(1000 + (m.id % 1000), n)
            } catch (e: SecurityException) {
                // Notifications are off.
            }
        }
    }

    /* ---------- notifications ---------- */

    private fun ongoing(title: String, text: String, matchId: Int? = null): Notification =
        Notification.Builder(this, CH_LIVE)
            .setSmallIcon(R.drawable.ic_ball)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(if (matchId != null) matchIntent(matchId) else mainIntent())
            .build()

    private fun updateOngoing(m: Match?, now: Long) {
        val (title, text) = if (m != null) {
            "${m.home.short} ${m.home.score} - ${m.away.score} ${m.away.short}" to "${clockText(m, now)} • ${m.comp}"
        } else {
            val next = matches.filter { it.state == Api.PRE && it.kickoff > now }.minByOrNull { it.kickoff }
            "Notch Live" to (next?.let { "الجاي: ${it.home.short} × ${it.away.short} • ${android.text.format.DateFormat.format("EEE h:mm a", it.kickoff)}" } ?: "مستني ماتشات فرقك")
        }
        val key = "$title|$text"
        if (key == lastNotif) return
        lastNotif = key
        nm.notify(NOTIF_ID, ongoing(title, text, m?.id))
    }

    private fun mainIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun matchIntent(id: Int): PendingIntent = PendingIntent.getActivity(
        this, id,
        Intent(this, MatchActivity::class.java).putExtra("id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openMatch(id: Int) {
        if (demo?.id == id) return
        try {
            startActivity(Intent(this, MatchActivity::class.java).putExtra("id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (e: Exception) {
            // Blocked from the background: the notification still opens it.
        }
    }

    /* ---------- demo (to check the position without a live match) ---------- */

    private fun startDemo(kind: String) {
        val teams = prefs.teams
        val a = teams.getOrNull(0) ?: Prefs.DEFAULT_TEAMS[0]
        val b = teams.getOrNull(1) ?: Prefs.DEFAULT_TEAMS[1]
        val now = System.currentTimeMillis()
        val home = Side(a.id, a.name, a.name, a.logo, 0xFFF87171.toInt(), 2)
        val away = Side(b.id, b.name, b.name, b.logo, 0xFF60A5FA.toInt(), 1)
        val m = Match(-1, "تجربة", Api.LIVE, "67'", "play", now - 67 * 60_000, home, away, emptyList())
        demo = m
        demoUntil = now + 20_000
        queue.removeAll { it.matchId == -1 }
        when (kind) {
            "goal" -> push(Burst("goal", "جووووول ⚽", "${a.name} • 67'", true, home.color, -1))
            "red" -> push(Burst("red", "🟥 طرد", "${b.name} • 67'", false, 0xFFF87171.toInt(), -1))
        }
        nextBurstAt = now + 2500
        render()
    }
}
