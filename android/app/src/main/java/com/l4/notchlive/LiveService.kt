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
 *   - one main match stays (favourite order, then pinned); others show only for their events
 *   - a finished one stays 30 s with "انتهت"
 *   - the notch only exists while a match is on
 * Runs as a foreground service with a quiet ongoing notification, so MIUI doesn't kill it.
 */
class LiveService : Service(), IslandView.Gestures {
    companion object {
        @Volatile
        var instance: LiveService? = null

        private const val CH_LIVE = "live"
        private const val CH_SCORE = "score"
        private const val CH_RESULT = "result"
        private const val PHONE_ID = -3
        /** Bursts that belong to no match. */
        private const val NEWS_ID = -2
        private const val NEWS_MS = 15 * 60_000L
        private const val CH_ALERT = "alerts"
        private const val NOTIF_ID = 1
        private const val UPDATE_ID = 2
        const val ACTION_REFRESH = "refresh"
        const val ACTION_DEMO = "demo"
        const val ACTION_STOP = "stop"

        /** Live polling and full-game refresh per speed: fast, balanced, saver. */
        private fun liveMs(speed: String) = when (speed) {
            "saver" -> 30_000L
            "balanced" -> 15_000L
            else -> 6_000L
        }

        private fun detailMs(speed: String) = when (speed) {
            "saver" -> 60_000L
            "balanced" -> 40_000L
            else -> 20_000L
        }

        /** The speed in force now: the chosen one, unless battery or mobile data says otherwise. */
        fun effectiveSpeed(ctx: Context): String {
            val p = Prefs(ctx)
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            if (p.autoSaver && !bm.isCharging && bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) in 1..20) return "saver"
            if (p.mobileBalanced && p.speed == "fast") {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                ) return "balanced"
            }
            return p.speed
        }
        private const val SLOW_MS = 5 * 60_000L
        private const val RETRY_MS = 30_000L
        private const val FINAL_MS = 30_000L
        private const val ROTATE_MS = 10_000L
        private const val BURST_MS = 6_500L
        private const val INFO_MS = 5_000L
        private const val CIRCLE_OPEN_MS = 8_000L

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
        L.load(this)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_LIVE, L.t("ch_live"), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SCORE, L.t("ch_score"), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, L.t("ch_alert"), NotificationManager.IMPORTANCE_HIGH).apply {
                // The app plays its own sounds and vibration.
                setSound(null, null)
                enableVibration(false)
            },
        )
        nm.createNotificationChannel(NotificationChannel(CH_RESULT, L.t("ch_result"), NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) })
        val n = ongoing("Notch Live", L.t("waiting"))
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
        sounds = Sounds(this)
        island = Island(this, prefs, this)
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
        refresh()
        main.post(tick)
        checkUpdate()
        net.schedule({ pollNews() }, 20, TimeUnit.SECONDS)
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

    /** For the app's home screen: what the notch shows now, and what's known. */
    fun snapshot(): Pair<Match?, List<Match>> = onScreen?.takeIf { it.id >= 0 && !idleNow } to matches

    /** The accessibility service came or went: move the window to the new host. */
    fun hostChanged() = main.post {
        island.reattach()
        render()
    }

    /* ---------- tap / swipe on the notch ---------- */

    override fun tap() {
        val m = onScreen ?: return
        val now = System.currentTimeMillis()
        if (idleNow && current == null) {
            // The always-on capsule: quick actions.
            idleMenu()
            return
        }
        if (current?.kind == "menu" || current?.kind == "stats") {
            current = null
            render()
            return
        }
        current?.takeIf { it.kind == "phone" }?.let { b ->
            current = null
            phoneIntents.remove(b)?.let {
                try {
                    it.send()
                } catch (e: Exception) {
                    // The notification is gone.
                }
            }
            render()
            return
        }
        current?.takeIf { it.kind == "news" && !it.expanded }?.let { b ->
            // First tap: the whole headline. The second one opens the article.
            b.expanded = true
            currentUntil = max(currentUntil, now + 12_000L)
            island.relayout()
            render()
            return
        }
        current?.takeIf { it.kind == "news" }?.url?.let { link ->
            current = null
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                // No browser.
            }
            render()
            return
        }
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

    /* ---------- long-press menu ---------- */

    /** What each row of the open menu does. */
    private var menuActions: List<String> = emptyList()

    private fun showMenu(id: Int, actions: List<String>) {
        menuActions = actions
        menuIdle = false
        val items = actions.map { a ->
            when (a) {
                "mute" -> L.t(if (id in muted) "menu_unmute" else "menu_mute")
                "pin" -> L.t(if (id in prefs.pinned) "menu_unpin" else "menu_pin")
                "sound" -> L.t(if (prefs.sound) "menu_sound_off" else "menu_sound_on")
                "saver" -> L.t(if (prefs.speed == "saver") "menu_saver_off" else "menu_saver_on")
                else -> L.t("menu_$a")
            }
        }
        showOverlay(Burst("menu", "", "", true, 0xFF3F3F46.toInt(), id, items = items), 6_000L)
    }

    /** The always-on capsule's quick actions. */
    private fun idleMenu() {
        val m = onScreen ?: return
        val acts = mutableListOf("sound", "saver", "hide_idle")
        if (lastResult() != null) acts += "last"
        acts += "app"
        showMenu(m.id, acts)
        menuIdle = true
    }

    /** The open menu belongs to the always-on capsule. */
    private var menuIdle = false

    private fun lastResult(): Match? = matches.filter { it.state == Api.POST }.maxByOrNull { it.kickoff }

    /** Mute (no sound or vibration) per match, for this session. */
    private val muted = HashSet<Int>()
    private var hiddenUntil = 0L

    override fun longPress() {
        val m = onScreen ?: return
        if (idleNow) {
            idleMenu()
            return
        }
        if (m.id < 0) return
        val acts = mutableListOf<String>()
        // Another followed match is live: make it the main one.
        if (liveNow().size > 1) acts += "switch"
        acts += listOf("mute", "pin", "hide")
        showMenu(m.id, acts)
    }

    override fun menuPick(index: Int) {
        val b = current?.takeIf { it.kind == "menu" } ?: return
        val id = b.matchId
        current = null
        when (menuActions.getOrNull(index)) {
            "mute" -> if (!muted.add(id)) muted.remove(id)
            "pin" -> {
                val p = prefs.pinned
                prefs.pinned = if (id in p) p - id else p + id
                refresh()
            }
            "hide" -> hiddenUntil = System.currentTimeMillis() + 10 * 60_000L
            "switch" -> {
                val live = liveNow()
                val i = live.indexOfFirst { it.id == id }
                live.getOrNull((i + 1) % live.size)?.let { prefs.primary = it.id }
            }
            "sound" -> prefs.sound = !prefs.sound
            "saver" -> prefs.speed = if (prefs.speed == "saver") "fast" else "saver"
            "hide_idle" -> idleHiddenUntil = System.currentTimeMillis() + 3 * 3600_000L
            "last" -> lastResult()?.let { open365(it) }
            "app" -> try {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                // Blocked from the background.
            }
        }
        render()
    }

    override fun pull(down: Boolean) {
        val m = onScreen ?: return
        if (!down) {
            if (current?.kind == "stats") {
                current = null
                render()
            }
            return
        }
        if (m.id < 0) return
        val b = Burst("stats", "", "", true, 0xFF3F3F46.toInt(), m.id)
        showOverlay(b, 10_000L)
        net.execute {
            val st = try {
                Api.stats(m.id, m.home.id)
            } catch (e: Exception) {
                emptyList()
            }
            main.post {
                b.stats = st
                island.relayout()
                render()
            }
        }
    }

    /** Menu and stats: shown at once over whatever is on, without sound. */
    private fun showOverlay(b: Burst, ms: Long) {
        val now = System.currentTimeMillis()
        current?.let { if (it.kind != "menu" && it.kind != "stats") queue.addFirst(it) }
        b.startedAt = now
        current = b
        currentUntil = now + ms
        infoMatch = null
        render()
    }

    override fun swipe(right: Boolean) {
        if (current?.kind == "menu" || current?.kind == "stats") {
            current = null
            render()
            return
        }
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
        val speed = effectiveSpeed(this)
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
                val stale = d == null || now - d.fetchedAt > detailMs(speed) ||
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
                if (list.any { it.state == Api.LIVE } || list.any { it.state == Api.PRE && it.kickoff - now < SOON_MS }) liveMs(speed) else 60_000L
            } else {
                val k = list.filter { it.state == Api.PRE && it.kickoff > now }.minOfOrNull { it.kickoff }
                if (k != null) (k - 75 * 60_000 - now).coerceIn(30_000L, SLOW_MS) else SLOW_MS
            }
            main.post { onData(list) }
        } catch (e: Exception) {
            next = RETRY_MS
        }
        countData()
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
                    push(Burst("whistle", L.t("full_time"), "${m.home.short} ${m.home.score} - ${m.away.score} ${m.away.short}", true, 0xFFE5E7EB.toInt(), m.id))
                    resultCard(m)
                }
                if (before.state == Api.PRE && m.state == Api.PRE && !before.hasLineups && m.hasLineups) {
                    push(Burst("notice", L.t("lineups"), L.f("lineups_sub", teams(m)), true, 0xFF34D399.toInt(), m.id))
                }
                if (before.state == Api.PRE && m.state == Api.LIVE) {
                    push(Burst("whistle", L.t("kickoff"), teams(m), true, 0xFFE5E7EB.toInt(), m.id))
                }
                for (home in listOf(true, false)) {
                    val diff = m.side(home).score - before.side(home).score
                    if (diff > 0 && m.state != Api.PRE) goal(m, home)
                    else if (diff < 0) push(Burst("var", L.t("goal_off"), m.side(home).short, home, 0xFFF87171.toInt(), m.id))
                }
                if (before.phase == "break" && m.state == Api.LIVE && m.phase == "play") {
                    val what = if (before.clock == Api.HALF_TIME) L.t("second_half") else teams(m)
                    push(Burst("whistle", L.t("resume"), what, true, 0xFFE5E7EB.toInt(), m.id))
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
        // The chosen main match goes back to automatic once it's over.
        val pri = prefs.primary
        if (pri != 0 && list.none { it.id == pri && it.state != Api.POST }) prefs.primary = 0

        shown.clear()
        for (m in list) shown[m.id] = m
        matches = list
        trackRun(list, now)
        render()
    }

    /**
     * Whose goal it is for the user: "for", "against" or "neutral" (a pinned match with no favourite).
     * With favourites on both sides, the one higher in the list is "yours".
     */
    private fun goalFor(m: Match, home: Boolean): String {
        val ids = prefs.teams.map { it.id }
        val me = ids.indexOf(m.side(home).id)
        val them = ids.indexOf(m.side(!home).id)
        return when {
            me < 0 && them < 0 -> "neutral"
            me < 0 -> "against"
            them < 0 -> "for"
            me < them -> "for"
            else -> "against"
        }
    }

    private fun goal(m: Match, home: Boolean) {
        val last = m.events.lastOrNull { it.kind == "goal" && it.home == home }
        val who = goalFor(m, home)
        val bad = who == "against"
        val b = Burst(
            "goal", if (bad) L.t("goal_against") else L.t("goal"), scorerLine(m, home, last), home, m.side(home).color, m.id,
            against = bad, neutral = who == "neutral",
        )
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
            e.own -> L.t("own_goal")
            e.pen -> L.t("pen_goal")
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
                "red" -> push(Burst("red", L.t("red"), who, e.home, 0xFFF87171.toInt(), m.id))
                "var" -> push(Burst("var", e.label.ifBlank { L.t("var") }, who, e.home, if (cancelled.containsMatchIn(e.label)) 0xFFF87171.toInt() else 0xFF60A5FA.toInt(), m.id))
                "pen" -> push(Burst("pen", e.label.ifBlank { L.t("pen") }, who, e.home, if (missed.containsMatchIn(e.label)) 0xFFF87171.toInt() else 0xFFFBBF24.toInt(), m.id))
            }
        }
    }

    private fun push(b: Burst) {
        // The provider can send a VAR event and the lowered score together: show it once.
        if (b.kind == "var" && (listOfNotNull(current) + queue).any { it.kind == "var" && it.matchId == b.matchId }) return
        queue.addLast(b)
    }

    /* ---------- phone notifications (beta) ---------- */

    private val phoneIntents = HashMap<Burst, PendingIntent>()

    /** From NotifListener: an app the user picked posted something. */
    fun phoneNotification(title: String, text: String, icon: android.graphics.Bitmap?, open: PendingIntent?) = main.post {
        if (!prefs.enabled || prefs.paused || !prefs.phone) return@post
        if (prefs.phoneFullOnly && island.statusBarVisible) return@post
        if (inCall()) return@post
        val b = Burst(
            "phone", title, if (prefs.phoneHide) L.t("new_message") else text, true, 0xFF34D399.toInt(), PHONE_ID, icon = icon,
        )
        if (open != null) phoneIntents[b] = open
        queue.addLast(b)
        render()
    }

    /* ---------- full-time card ---------- */

    /** A notification with a picture of the final score, to share. */
    private fun resultCard(m: Match) {
        if (!prefs.cards || m.id < 0) return
        val favs = prefs.teams.map { it.id }.toSet()
        if (m.home.id !in favs && m.away.id !in favs && m.id !in prefs.pinned) return
        val file = try {
            ShareCard.render(this, m)
        } catch (e: Exception) {
            return
        }
        val uri = android.net.Uri.parse("content://$packageName.share/${file.name}")
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri("", uri)
        val chooser = Intent.createChooser(send, L.t("share")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val pi = PendingIntent.getActivity(this, 5000 + m.id % 1000, chooser, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val bmp = android.graphics.BitmapFactory.decodeFile(file.path)
        val n = Notification.Builder(this, CH_RESULT)
            .setSmallIcon(R.drawable.ic_ball)
            .setContentTitle(L.f("card_title", "${m.home.short} ${m.home.score} - ${m.away.score} ${m.away.short}"))
            .setContentText(L.t("card_text"))
            .setLargeIcon(bmp)
            .setStyle(Notification.BigPictureStyle().bigPicture(bmp))
            .setContentIntent(pi)
            .addAction(Notification.Action.Builder(null, L.t("share"), pi).build())
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(3000 + m.id % 1000, n)
        } catch (e: SecurityException) {
            // Notifications are off.
        }
    }

    /* ---------- news about the favourite teams ---------- */

    private fun newsLang(): Int = when (prefs.newsLang) {
        "ar" -> 27
        "en" -> 1
        else -> L.apiLang
    }

    /** A stand-in "match" so the notch can draw a news burst. */
    private fun newsMatch(b: Burst): Match {
        val side = Side(0, "", "", b.logo ?: "", b.color, 0)
        return Match(NEWS_ID, "", Api.POST, "", "", 0L, side, side, emptyList())
    }

    /** Every 15 min: new headlines about the favourite teams (the first check only remembers them). */
    private fun pollNews() {
        try {
            if (prefs.enabled && prefs.news && effectiveSpeed(this) != "saver") {
                val seen = prefs.seenNews.toMutableSet()
                val first = seen.isEmpty()
                val fresh = ArrayList<Pair<TeamOption, Api.News>>()
                for (t in prefs.teams) {
                    val items = try {
                        Api.news(t.id, newsLang()).take(6)
                    } catch (e: Exception) {
                        continue
                    }
                    for (n in items) {
                        if (seen.add(n.id) && !first && System.currentTimeMillis() - n.at < 6 * 3600_000L) fresh += t to n
                    }
                }
                // Keep the list from growing forever.
                prefs.seenNews = if (seen.size > 300) seen.toList().takeLast(200).toSet() else seen
                val pick = fresh.sortedByDescending { it.second.at }.take(2)
                if (pick.isNotEmpty()) main.post {
                    for ((t, n) in pick) {
                        queue.addLast(Burst("news", L.f("news_about", t.name), n.title, true, 0xFF60A5FA.toInt(), NEWS_ID, logo = t.logo, url = n.url))
                    }
                }
            }
        } catch (e: Exception) {
            // Try again next time.
        }
        if (instance === this) net.schedule({ pollNews() }, NEWS_MS, TimeUnit.MILLISECONDS)
    }

    /* ---------- data per day ---------- */

    private var lastTraffic = -1L

    /** Adds what was downloaded since last time to today's total (kept 14 days). */
    private fun countData() {
        val now = traffic()
        if (lastTraffic >= 0 && now >= lastTraffic) {
            val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val d = prefs.daily.toMutableMap()
            d[day] = (d[day] ?: 0L) + (now - lastTraffic)
            prefs.daily = d
        }
        lastTraffic = now
    }

    /** The feed's Arabic status words, in the app's language. */
    private fun localClock(c: String): String = when (c) {
        Api.HALF_TIME -> L.t("half_time")
        "انتهت" -> L.t("ft")
        "قبل الإضافي" -> if (L.ar) c else "Before ET"
        "قبل الترجيح" -> if (L.ar) c else "Before pens"
        else -> c
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
            return if (mins > 0) L.f("in_min", mins) else L.t("now")
        }
        if (m.state != Api.LIVE || m.phase != "play") return localClock(m.clock)
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

    /** Priority of a match: the favourite highest in the list first, then pinned ones. */
    private fun rank(m: Match): Int {
        val ids = prefs.teams.map { it.id }
        val i = listOf(ids.indexOf(m.home.id), ids.indexOf(m.away.id)).filter { it >= 0 }.minOrNull()
        return i ?: if (m.id in prefs.pinned) 1000 else 2000
    }

    /** Followed matches on now (and the ones just over), main one first. */
    private fun liveNow(list: List<Match> = matches + listOfNotNull(demo?.takeIf { System.currentTimeMillis() < demoUntil })): List<Match> {
        val now = System.currentTimeMillis()
        return list.filter { it.state == Api.LIVE || (it.state == Api.POST && now - (endedAt[it.id] ?: 0L) < FINAL_MS) }
            .sortedWith(compareBy({ if (it.id == prefs.primary) -1 else 0 }, { if (it.state == Api.LIVE) 0 else 1 }, { rank(it) }, { it.kickoff }, { it.id }))
    }

    /**
     * One main match stays on the notch (no more taking turns): the user's pick from the menu, else
     * the favourite highest in the list, else a pinned one. Others only appear for their events.
     */
    private fun featured(list: List<Match>, now: Long): Match? {
        current?.let { b -> list.firstOrNull { it.id == b.matchId }?.let { return it } }
        if (now < infoUntil) infoMatch?.let { id -> list.firstOrNull { it.id == id }?.let { return it } }
        val live = liveNow(list)
        if (live.isEmpty()) return list.filter { soon(it, now) }.sortedWith(compareBy({ rank(it) }, { it.kickoff })).firstOrNull()
        return live.first()
    }

    /** The always-on capsule (option): the next match, else the last result. */
    private var idleNow = false
    private var idleHiddenUntil = 0L

    private fun idleMatch(now: Long): Match? {
        if (!prefs.idleNotch || now < idleHiddenUntil) return null
        val next = matches.filter { it.state == Api.PRE && it.kickoff > now }.sortedWith(compareBy({ it.kickoff }, { rank(it) })).firstOrNull()
        return next ?: lastResult()
    }

    /** "بعد يومين", "غداً 9:00 م", "اليوم 9:00 م" for the capsule; the result's "انتهت". */
    private fun idleClock(m: Match, now: Long): String {
        if (m.state != Api.PRE) return localClock(m.clock)
        val zone = java.util.TimeZone.getDefault()
        val day = 86_400_000L
        val today = (now + zone.getOffset(now)) / day
        val that = (m.kickoff + zone.getOffset(m.kickoff)) / day
        val time = android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(m.kickoff))
        return when (val n = (that - today).toInt()) {
            0 -> if (m.kickoff - now <= SOON_MS) clockText(m, now) else L.f("idle_today", time)
            1 -> L.f("idle_tomorrow", time)
            2 -> L.t("idle_2days")
            else -> L.f("idle_days", n)
        }
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
            val owner = if (b.kind == "news" || b.kind == "phone") newsMatch(b) else list.firstOrNull { it.id == b.matchId }
            if (owner != null) {
                current = b
                b.startedAt = now
                currentUntil = now + if (b.kind == "news" || b.kind == "phone") 8_000L else BURST_MS
                infoMatch = null
                announce(b, owner)
            }
        }
        val news = current?.takeIf { it.kind == "news" || it.kind == "phone" }
        var m = if (news != null) newsMatch(news) else featured(list, now)
        idleNow = false
        val idleMenuOpen = current?.kind == "menu" && menuIdle
        if ((m == null && current == null) || idleMenuOpen) {
            idleMatch(now)?.let {
                m = it
                idleNow = true
            }
        }
        onScreen = m
        // Xiaomi's island is busy (a call or music): circle beside it, or hidden if that's off.
        val busy = inCall() || audio.isMusicActive
        val wasCircle = circleNow
        circleNow = busy && prefs.circle && island.portrait()
        if (wasCircle && !circleNow) infoMatch = null
        val hidden = !prefs.enabled || prefs.paused || now < hiddenUntil || (busy && !prefs.circle && island.portrait()) ||
            // The capsule steps aside for Xiaomi's island.
            (idleNow && busy && current == null)
        island.calm = effectiveSpeed(this) == "saver"
        val shownM = m
        if (shownM == null || hidden) {
            island.update(null, "", "", null, false, false, false)
        } else {
            val others = if (shownM.id < 0 || idleNow) 0 else (liveNow(list).count { it.state == Api.LIVE } - 1).coerceAtLeast(0)
            island.update(
                shownM, if (idleNow) idleClock(shownM, now) else clockText(shownM, now), if (idleNow) "" else addedText(shownM, now),
                current?.takeIf { it.matchId == shownM.id },
                infoMatch == shownM.id && !idleNow,
                prefs.tucked && !circleNow && !idleNow,
                circleNow && !idleNow,
                others = others,
                idle = idleNow,
            )
        }
        updateOngoing(if (idleNow) null else shownM, now)
    }

    private fun announce(b: Burst, m: Match) {
        if (b.kind == "phone") return
        val quiet = b.matchId in muted
        if (prefs.sound && !inCall() && !quiet) sounds.play(if (b.against) "conceded" else if (b.neutral) "neutral" else b.kind)
        if (prefs.vibrate && !quiet && (b.kind == "goal" || b.kind == "red")) buzz(b.kind == "goal")
        // The notch can't show it (no permission, a call, landscape): a normal notification instead.
        if (!island.canShow() || !island.showing || prefs.paused || inCall() || System.currentTimeMillis() < hiddenUntil) {
            if (b.kind == "notice" || b.kind == "whistle" || b.kind == "news") return
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
            prefs.lastBytes = traffic() - runBytes
            prefs.lastBattery = if (runCharged) -2 else drop.coerceAtLeast(0)
            prefs.report = "$mins min • ${"%.1f".format(mb)} MB"
            runStart = 0L
            // Next time a match starts, the notch is out again even if it was swiped away.
            prefs.tucked = false
        }
    }

    /* ---------- notifications ---------- */

    private fun ongoing(title: String, text: String, m: Match? = null): Notification {
        val live = m != null && m.id >= 0
        val b = Notification.Builder(this, if (live) CH_SCORE else CH_LIVE)
            .setSmallIcon(R.drawable.ic_ball)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(if (live) matchIntent(m!!) else mainIntent())
        if (live) {
            // A small scoreboard, which the lock screen shows too.
            val v = android.widget.RemoteViews(packageName, R.layout.notif_score)
            v.setTextViewText(R.id.home_name, m!!.home.short)
            v.setTextViewText(R.id.away_name, m.away.short)
            v.setTextViewText(R.id.score, if (m.state == Api.PRE) "×" else "${m.home.score} - ${m.away.score}")
            v.setTextViewText(R.id.status, text)
            Logos.get(m.home.logo) { lastNotif = "" }?.let { v.setImageViewBitmap(R.id.home_logo, it) }
            Logos.get(m.away.logo) { lastNotif = "" }?.let { v.setImageViewBitmap(R.id.away_logo, it) }
            b.setCustomContentView(v)
            b.setCustomBigContentView(v)
            b.setStyle(Notification.DecoratedCustomViewStyle())
        }
        return b.build()
    }

    private fun updateOngoing(m: Match?, now: Long) {
        val shown = m?.takeIf { it.id >= 0 }
        val (title, text) = if (shown != null) {
            "${shown.home.short} ${shown.home.score} - ${shown.away.score} ${shown.away.short}" to "${clockText(shown, now)} • ${shown.comp}"
        } else {
            val next = matches.filter { it.state == Api.PRE && it.kickoff > now }.minByOrNull { it.kickoff }
            "Notch Live" to (next?.let { L.f("next_match", teams(it), android.text.format.DateFormat.format("EEE h:mm a", it.kickoff)) } ?: L.t("waiting"))
        }
        val key = "$title|$text"
        if (key == lastNotif) return
        lastNotif = key
        nm.notify(NOTIF_ID, ongoing(title, text, shown))
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
                .setContentTitle(L.f("update_title", r.version))
                .setContentText(L.t("update_text"))
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
            -1, if (L.ar) "تجربة" else "Test", Api.LIVE, "92'", "play", now - 95 * 60_000, home, away, evs,
            minute = 92, added = 4, clockSec = 91 * 60 + 10, fetchedAt = now, period = 2,
        )
        demo = m
        demoUntil = now + 25_000
        queue.removeAll { it.matchId == -1 }
        when (kind) {
            "goal" -> push(Burst("goal", L.t("goal"), "تريزيجيه • 88'", true, home.color, -1))
            "red" -> push(Burst("red", L.t("red"), "كوندي • 70'", false, 0xFFF87171.toInt(), -1))
            "whistle" -> push(Burst("whistle", L.t("resume"), L.t("second_half"), true, 0xFFE5E7EB.toInt(), -1))
            "against" -> push(Burst("goal", L.t("goal_against"), "يامال • 88'", false, away.color, -1, against = true))
            "news" -> queue.addLast(
                Burst("news", L.f("news_about", a.name), if (L.ar) "تجربة: عنوان خبر طويل بعض الشيء لنرى شكله على سطرين داخل النوتش" else "Test: a longish headline to see how it looks on two lines in the notch", true, 0xFF60A5FA.toInt(), NEWS_ID, logo = a.logo, url = "https://www.365scores.com/ar/news"),
            )
            "lineups" -> {
                // Before kickoff: the countdown, then the line-ups notice.
                demo = m.copy(state = Api.PRE, clock = "", phase = "", kickoff = now + 12 * 60_000, events = emptyList(), minute = null, added = null, clockSec = null)
                push(Burst("notice", L.t("lineups"), L.f("lineups_sub", "${a.name} × ${b.name}"), true, 0xFF34D399.toInt(), -1))
            }
        }
        nextBurstAt = now + 2500
        render()
    }
}
