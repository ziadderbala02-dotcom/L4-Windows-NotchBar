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
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

/*
 * Watches the favourite teams' matches and pinned ones (same rules as the Windows notch):
 *   - polls 365Scores every 6 s while a match is live or about to start, else every few minutes
 *   - goal / goal ruled out / red card / VAR / penalty / kickoff / second half / full time → a burst
 *   - tap: the info view for 5 s; tap again: the match in 365Scores
 *   - swipe right: tucked to a small mark; swipe left: back. Bursts still show while tucked.
 *   - several live matches take turns every 10 s; a finished one stays 30 s with "انتهت"
 *   - the notch only exists while a match is on
 * Runs as a foreground service with a quiet ongoing notification, so MIUI doesn't kill it.
 */
class LiveService : Service(), IslandView.Gestures {
    companion object {
        @Volatile
        var instance: LiveService? = null

        private const val CH_LIVE = "live"
        private const val CH_ALERT = "alerts"
        private const val NOTIF_ID = 1
        private const val UPDATE_ID = 2
        const val ACTION_REFRESH = "refresh"
        const val ACTION_DEMO = "demo"
        const val ACTION_STOP = "stop"

        private const val FAST_MS = 6_000L
        private const val SLOW_MS = 5 * 60_000L
        private const val RETRY_MS = 30_000L
        private const val FINAL_MS = 30_000L
        private const val ROTATE_MS = 10_000L
        private const val BURST_MS = 6_500L
        private const val INFO_MS = 5_000L
        private const val CIRCLE_OPEN_MS = 8_000L
        /** Scorers and cards are refetched this often (or at once when the score changes). */
        private const val DETAIL_MS = 20_000L
        /** The countdown shows from this long before kickoff. */
        private const val SOON_MS = 15 * 60_000L

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
    private val queue = ArrayDeque<Burst>()
    private var current: Burst? = null
    private var currentUntil = 0L
    private var nextBurstAt = 0L
    private var infoMatch: Int? = null
    private var infoUntil = 0L
    private var onScreen: Match? = null
    private var circleNow = false

    // Polling (net thread): the last full game per id, and the teams of pinned matches.
    private val details = java.util.concurrent.ConcurrentHashMap<Int, Match>()
    private val pinTeams = java.util.concurrent.ConcurrentHashMap<Int, Pair<Int, Int>>()
    private var demo: Match? = null
    private var demoUntil = 0L
    private var lastNotif = ""

    // Battery / data report for the current run of live matches.
    private var runStart = 0L
    private var runBattery = -1
    private var runBytes = 0L
    private var runCharged = false

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
                // The app plays its own sounds and vibration.
                setSound(null, null)
                enableVibration(false)
            },
        )
        val n = ongoing("Notch Live", "مستني ماتشات فرقك")
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
        sounds = Sounds(this)
        island = Island(this, prefs, this)
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
        refresh()
        main.post(tick)
        checkUpdate()
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

    /* ---------- tap / swipe on the notch ---------- */

    override fun tap() {
        val m = onScreen ?: return
        val now = System.currentTimeMillis()
        if (circleNow) {
            // Beside Xiaomi's island: tap opens the full view over it, tap again closes it.
            if (infoMatch == m.id) {
                infoMatch = null
            } else {
                infoMatch = m.id
                infoUntil = now + CIRCLE_OPEN_MS
            }
        } else if (prefs.tucked && current == null && infoMatch == null) {
            prefs.tucked = false
        } else if ((infoMatch == m.id && now < infoUntil) || current?.matchId == m.id) {
            // Second tap (or a tap on a burst): the match in 365Scores.
            infoMatch = null
            open365(m)
        } else {
            infoMatch = m.id
            infoUntil = now + INFO_MS
        }
        render()
    }

    override fun swipe(right: Boolean) {
        if (circleNow) {
            infoMatch = null
            render()
            return
        }
        prefs.tucked = right
        infoMatch = null
        render()
    }

    private fun open365(m: Match) {
        if (m.id < 0) return
        try {
            startActivity(MatchActivity.intent(this, m))
        } catch (e: Exception) {
            // Blocked from the background: the notification still opens it.
        }
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
            val favs = prefs.teams.map { it.id }.toSet()
            val pins = prefs.pinned
            // One light request for the favourite teams and the teams of pinned matches.
            val ids = (favs + pins.flatMap { pinTeams[it]?.toList() ?: emptyList() }).toList()
            var list = if (ids.isEmpty()) emptyList() else Api.teamGames(ids)
            list = list.filter { it.home.id in favs || it.away.id in favs || it.id in pins }
            // Pinned matches we don't know the teams of yet: fetch them whole once.
            val extra = pins.filter { id -> list.none { it.id == id } }.mapNotNull { id ->
                try {
                    Api.game(id).also {
                        details[id] = it
                        pinTeams[id] = it.home.id to it.away.id
                    }
                } catch (e: Exception) {
                    null
                }
            }
            list = list + extra
            val now = System.currentTimeMillis()
            // Live ones: the light list has score and minute; scorers and cards come from the full
            // game every 20 s, or at once when the score or phase changes.
            list = list.map { m ->
                if (m.state != Api.LIVE || m in extra) return@map m
                val d = details[m.id]
                val stale = d == null || now - d.fetchedAt > DETAIL_MS ||
                    d.home.score != m.home.score || d.away.score != m.away.score || d.phase != m.phase || d.state != m.state
                if (stale) {
                    try {
                        return@map Api.game(m.id).copy(comp = m.comp).also { details[m.id] = it }
                    } catch (e: Exception) {
                        // Use what we had.
                    }
                }
                if (d == null) m else m.copy(
                    events = d.events,
                    compSlug = m.compSlug.ifBlank { d.compSlug },
                    added = m.added ?: d.added,
                    minute = listOfNotNull(m.minute, d.minute).maxOrNull(),
                    clockSec = if (m.phase == "play") d.clockSec else null,
                    fetchedAt = d.fetchedAt,
                    period = if (m.period != 0) m.period else d.period,
                )
            }
            for (id in details.keys) if (list.none { it.id == id && it.state == Api.LIVE } && id !in pins) details.remove(id)
            val hot = list.any {
                it.state == Api.LIVE || (it.state == Api.PRE && it.kickoff - now < 75 * 60_000 && now - it.kickoff < 3 * 3600_000)
            }
            next = if (hot) {
                // Before kickoff (line-ups, countdown) a minute is plenty.
                if (list.any { it.state == Api.LIVE } || list.any { it.state == Api.PRE && it.kickoff - now < SOON_MS }) FAST_MS else 60_000L
            } else {
                val k = list.filter { it.state == Api.PRE && it.kickoff > now }.minOfOrNull { it.kickoff }
                if (k != null) (k - 75 * 60_000 - now).coerceIn(30_000L, SLOW_MS) else SLOW_MS
            }
            main.post { onData(list) }
        } catch (e: Exception) {
            next = RETRY_MS
        }
        if (g == gen) net.schedule({ poll(g) }, next, TimeUnit.MILLISECONDS)
    }

    /* ---------- spotting what happened ---------- */

    private fun teams(m: Match) = "${m.home.short} × ${m.away.short}"

    private fun onData(list: List<Match>) {
        val now = System.currentTimeMillis()
        for (m in list) {
            val before = shown[m.id]
            if (before != null) {
                if (before.state == Api.LIVE && m.state == Api.POST) {
                    endedAt[m.id] = now
                    push(Burst("whistle", "انتهت المباراة", "${m.home.short} ${m.home.score} - ${m.away.score} ${m.away.short}", true, 0xFFE5E7EB.toInt(), m.id))
                }
                if (before.state == Api.PRE && m.state == Api.PRE && !before.hasLineups && m.hasLineups) {
                    push(Burst("notice", "التشكيل نزل", "${teams(m)} • دوس تشوفه", true, 0xFF34D399.toInt(), m.id))
                }
                if (before.state == Api.PRE && m.state == Api.LIVE) {
                    push(Burst("whistle", "الماتش بدأ", teams(m), true, 0xFFE5E7EB.toInt(), m.id))
                }
                for (home in listOf(true, false)) {
                    val diff = m.side(home).score - before.side(home).score
                    if (diff > 0 && m.state != Api.PRE) goal(m, home)
                    else if (diff < 0) push(Burst("var", "الجون اتلغى", m.side(home).short, home, 0xFFF87171.toInt(), m.id))
                }
                if (before.phase == "break" && m.state == Api.LIVE && m.phase == "play") {
                    val what = if (before.clock == Api.HALF_TIME) "الشوط التاني بدأ" else teams(m)
                    push(Burst("whistle", "استكمال المباراة", what, true, 0xFFE5E7EB.toInt(), m.id))
                }
            }
            spotIncidents(m)
        }
        // Pinned matches leave once they're over (after the final 30 s).
        val pins = prefs.pinned
        val done = pins.filter { id ->
            val m = list.firstOrNull { it.id == id } ?: return@filter false
            m.state == Api.POST && now - (endedAt[id] ?: 0L) > FINAL_MS
        }
        if (done.isNotEmpty()) prefs.pinned = pins - done.toSet()

        shown.clear()
        for (m in list) shown[m.id] = m
        matches = list
        trackRun(list, now)
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
            e.own -> " (عكسي)"
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
            if (known == null && (m.minute ?: 99) <= 3) seen[m.id] = HashSet()
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

    /* ---------- the clock ---------- */

    /** The minute being played: 91', 92' … in added time. Counts on between polls. */
    private fun minuteNow(m: Match, now: Long): Int? {
        val base = m.minute ?: return null
        val sec = m.clockSec ?: return base
        val running = (sec + (now - m.fetchedAt) / 1000).toInt() / 60 + 1
        // Never run more than 2 minutes past the last poll (a stalled feed).
        return max(base, minOf(running, base + 2))
    }

    private fun clockText(m: Match, now: Long): String {
        if (m.state == Api.PRE) {
            val mins = ((m.kickoff - now + 59_999) / 60_000).toInt()
            return if (mins > 0) "بعد ${mins}د" else "دلوقتي"
        }
        if (m.state != Api.LIVE || m.phase != "play") return m.clock
        val n = minuteNow(m, now) ?: return m.clock
        return "$n'"
    }

    /** "+4" next to the minute, once added time is on and the feed says how much. */
    private fun addedText(m: Match, now: Long): String {
        val a = m.added ?: return ""
        val n = minuteNow(m, now) ?: return ""
        val inAdded = when (m.period) {
            1 -> n > 45
            2 -> n > 90
            else -> n > 90
        }
        return if (inAdded) "+$a" else ""
    }

    /* ---------- what the notch shows ---------- */

    private fun soon(m: Match, now: Long) =
        m.state == Api.PRE && m.kickoff - now <= SOON_MS && now - m.kickoff < 30 * 60_000

    private fun featured(list: List<Match>, now: Long): Match? {
        current?.let { b -> list.firstOrNull { it.id == b.matchId }?.let { return it } }
        if (now < infoUntil) infoMatch?.let { id -> list.firstOrNull { it.id == id }?.let { return it } }
        val live = list.filter { it.state == Api.LIVE || (it.state == Api.POST && now - (endedAt[it.id] ?: 0L) < FINAL_MS) }
        if (live.isEmpty()) return list.filter { soon(it, now) }.minByOrNull { it.kickoff }
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
        if (infoMatch != null && now >= infoUntil) infoMatch = null
        val list = matches + listOfNotNull(demo?.takeIf { now < demoUntil })
        if (current == null && queue.isNotEmpty() && now >= nextBurstAt) {
            val b = queue.removeFirst()
            // Only if its match is still around (a burst for a match that ended long ago is dropped).
            if (list.any { it.id == b.matchId }) {
                current = b
                currentUntil = now + BURST_MS
                infoMatch = null
                announce(b, list.first { it.id == b.matchId })
            }
        }
        val m = featured(list, now)
        onScreen = m
        // Xiaomi's island is busy (a call or music): circle beside it, or hidden if that's off.
        val busy = inCall() || audio.isMusicActive
        val wasCircle = circleNow
        circleNow = busy && prefs.circle && island.portrait()
        if (wasCircle && !circleNow) infoMatch = null
        val hidden = !prefs.enabled || prefs.paused || (busy && !prefs.circle && island.portrait())
        if (m == null || hidden) {
            island.update(null, "", "", null, false, false, false)
        } else {
            island.update(
                m, clockText(m, now), addedText(m, now),
                current?.takeIf { it.matchId == m.id },
                infoMatch == m.id,
                prefs.tucked && !circleNow,
                circleNow,
            )
        }
        updateOngoing(m, now)
    }

    private fun announce(b: Burst, m: Match) {
        if (prefs.sound && !inCall()) sounds.play(b.kind)
        if (prefs.vibrate && (b.kind == "goal" || b.kind == "red")) buzz(b.kind == "goal")
        // The notch can't show it (no permission, a call, landscape): a normal notification instead.
        if (!island.canShow() || !island.showing || prefs.paused || inCall()) {
            if (b.kind == "notice" || b.kind == "whistle") return
            val n = Notification.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_ball)
                .setContentTitle("${b.title}  ${m.home.short} ${m.home.score} - ${m.away.score} ${m.away.short}")
                .setContentText(b.sub)
                .setAutoCancel(true)
                .setContentIntent(matchIntent(m))
                .build()
            try {
                nm.notify(1000 + (m.id % 1000), n)
            } catch (e: SecurityException) {
                // Notifications are off.
            }
        }
    }

    private fun buzz(goal: Boolean) {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        if (!v.hasVibrator()) return
        val pattern = if (goal) longArrayOf(0, 120, 80, 120, 80, 260) else longArrayOf(0, 300)
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    /* ---------- battery and data report ---------- */

    /** Bytes this app really sent and received (compressed, as on the wire). */
    private fun traffic(): Long {
        val uid = android.os.Process.myUid()
        val rx = android.net.TrafficStats.getUidRxBytes(uid)
        val tx = android.net.TrafficStats.getUidTxBytes(uid)
        return if (rx < 0 || tx < 0) Api.bytes.get() else rx + tx
    }

    private fun battery(): Int =
        (getSystemService(BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun charging(): Boolean = (getSystemService(BATTERY_SERVICE) as BatteryManager).isCharging

    /** From the first live match to the last one ending: data used and battery drop. */
    private fun trackRun(list: List<Match>, now: Long) {
        val live = list.any { it.state == Api.LIVE }
        if (live && runStart == 0L) {
            runStart = now
            runBattery = battery()
            runBytes = traffic()
            runCharged = charging()
        } else if (live && charging()) {
            runCharged = true
        } else if (!live && runStart != 0L) {
            val mins = (now - runStart) / 60_000
            val mb = (traffic() - runBytes) / 1_048_576.0
            val drop = runBattery - battery()
            val bat = if (runCharged) "كان على الشاحن" else "البطارية نزلت $drop%"
            prefs.report = "آخر ماتشات: $mins دقيقة • ${"%.1f".format(mb)} ميجا داتا • $bat (مع استخدامك العادي للموبايل)"
            runStart = 0L
            // Next time a match starts, the notch is out again even if it was swiped away.
            prefs.tucked = false
        }
    }

    /* ---------- notifications ---------- */

    private fun ongoing(title: String, text: String, m: Match? = null): Notification =
        Notification.Builder(this, CH_LIVE)
            .setSmallIcon(R.drawable.ic_ball)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(if (m != null && m.id >= 0) matchIntent(m) else mainIntent())
            .build()

    private fun updateOngoing(m: Match?, now: Long) {
        val (title, text) = if (m != null) {
            "${m.home.short} ${m.home.score} - ${m.away.score} ${m.away.short}" to "${clockText(m, now)} • ${m.comp}"
        } else {
            val next = matches.filter { it.state == Api.PRE && it.kickoff > now }.minByOrNull { it.kickoff }
            "Notch Live" to (next?.let { "الجاي: ${teams(it)} • ${android.text.format.DateFormat.format("EEE h:mm a", it.kickoff)}" } ?: "مستني ماتشات فرقك")
        }
        val key = "$title|$text"
        if (key == lastNotif) return
        lastNotif = key
        nm.notify(NOTIF_ID, ongoing(title, text, m))
    }

    private fun mainIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun matchIntent(m: Match): PendingIntent = PendingIntent.getActivity(
        this, m.id, MatchActivity.intent(this, m),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /* ---------- new version (checked at most every 12 h) ---------- */

    private fun checkUpdate() {
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheck < 12 * 3600_000L) return
        prefs.lastUpdateCheck = now
        net.execute {
            val r = try {
                Updater.latest()
            } catch (e: Exception) {
                null
            } ?: return@execute
            if (!Updater.newer(r.version, Updater.current(this))) return@execute
            val n = Notification.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_ball)
                .setContentTitle("نسخة جديدة من Notch Live: ${r.version}")
                .setContentText("افتح التطبيق ودوس تحديث")
                .setAutoCancel(true)
                .setContentIntent(mainIntent())
                .build()
            try {
                nm.notify(UPDATE_ID, n)
            } catch (e: SecurityException) {
                // Notifications are off; the app shows it too.
            }
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
        val evs = listOf(
            Ev("d1", "goal", true, "23'", "صلاح", null, "", own = false, pen = false, at = 23.0),
            Ev("d2", "goal", false, "51'", "يامال", null, "", own = false, pen = true, at = 51.0),
            Ev("d3", "goal", true, "88'", "تريزيجيه", null, "", own = false, pen = false, at = 88.0),
            Ev("d4", "red", false, "70'", "كوندي", null, "", own = false, pen = false, at = 70.0),
        )
        val m = Match(
            -1, "تجربة", Api.LIVE, "92'", "play", now - 95 * 60_000, home, away, evs,
            minute = 92, added = 4, clockSec = 91 * 60 + 10, fetchedAt = now, period = 2,
        )
        demo = m
        demoUntil = now + 25_000
        queue.removeAll { it.matchId == -1 }
        when (kind) {
            "goal" -> push(Burst("goal", "جووووول ⚽", "تريزيجيه • 88'", true, home.color, -1))
            "red" -> push(Burst("red", "🟥 طرد", "كوندي • 70'", false, 0xFFF87171.toInt(), -1))
            "whistle" -> push(Burst("whistle", "استكمال المباراة", "الشوط التاني بدأ", true, 0xFFE5E7EB.toInt(), -1))
            "lineups" -> {
                // Before kickoff: the countdown, then the line-ups notice.
                demo = m.copy(state = Api.PRE, clock = "", phase = "", kickoff = now + 12 * 60_000, events = emptyList(), minute = null, added = null, clockSec = null)
                push(Burst("notice", "التشكيل نزل", "${a.name} × ${b.name} • دوس تشوفه", true, 0xFF34D399.toInt(), -1))
            }
        }
        nextBurstAt = now + 2500
        render()
    }
}
