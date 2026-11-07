package com.l4.notchlive

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One headline for the home screen's news list. */
data class NewsItem(val team: String, val logo: String, val title: String, val url: String, val at: Long)

/** Settings and favourite teams, in SharedPreferences. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("notch", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = p.getBoolean("enabled", true)
        set(v) = p.edit().putBoolean("enabled", v).apply()

    var sound: Boolean
        get() = p.getBoolean("sound", true)
        set(v) = p.edit().putBoolean("sound", v).apply()

    /** Hide the notch while audio plays (Xiaomi's own island takes the spot then). */
    var hideOnAudio: Boolean
        get() = p.getBoolean("hideOnAudio", false)
        set(v) = p.edit().putBoolean("hideOnAudio", v).apply()

    /** While Xiaomi's own island is busy (music, a call): shrink to a small circle on its right. */
    var circle: Boolean
        get() = p.getBoolean("circle", true)
        set(v) = p.edit().putBoolean("circle", v).apply()

    /** News about the favourite teams in the notch. */
    var news: Boolean
        get() = p.getBoolean("news", true)
        set(v) = p.edit().putBoolean("news", v).apply()

    /** News already seen (ids), so only new ones show. */
    var seenNews: Set<String>
        get() = p.getStringSet("seenNews", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("seenNews", v).apply()

    /** Hidden from the Quick Settings tile: no notch, alerts come as notifications. */
    var paused: Boolean
        get() = p.getBoolean("paused", false)
        set(v) = p.edit().putBoolean("paused", v).apply()

    /** Vertical nudge in dp (-20..20) around the camera. */
    var offsetY: Int
        get() = p.getInt("offsetY", 0)
        set(v) = p.edit().putInt("offsetY", v).apply()

    /** Horizontal nudge in dp (-40..40), for phones whose camera is reported a little off. */
    var offsetX: Int
        get() = p.getInt("offsetX", 0)
        set(v) = p.edit().putInt("offsetX", v).apply()

    /** Extra room (dp, -40..60) the notch may take on each side, past the default status-bar edges. */
    var roomLeft: Int
        get() = p.getInt("roomLeft", 0)
        set(v) = p.edit().putInt("roomLeft", v).apply()

    var roomRight: Int
        get() = p.getInt("roomRight", 0)
        set(v) = p.edit().putInt("roomRight", v).apply()

    /** Full screen (no status bar): the notch takes its full size with team names. Off by default. */
    var roomyFullscreen: Boolean
        get() = p.getBoolean("roomyFullscreen", false)
        set(v) = p.edit().putBoolean("roomyFullscreen", v).apply()

    /** The always-on capsule when no match is live (next match, news). Off by default. */
    var idleNotch: Boolean
        get() = p.getBoolean("idleNotch", false)
        set(v) = p.edit().putBoolean("idleNotch", v).apply()

    /** The match the user chose as the main one (from the long-press menu); 0 = automatic. */
    var primary: Int
        get() = p.getInt("primary", 0)
        set(v) = p.edit().putInt("primary", v).apply()

    /** News language: "app" (same as the app), "ar" or "en". */
    var newsLang: String
        get() = p.getString("newsLang", "app") ?: "app"
        set(v) = p.edit().putString("newsLang", v).apply()

    /** Per kind ("goal", "card", "whistle", "news"): "app", "phone" (notification sound) or "off". */
    fun soundFor(kind: String): String = p.getString("snd_$kind", "app") ?: "app"
    fun setSoundFor(kind: String, v: String) = p.edit().putString("snd_$kind", v).apply()

    /** 0..100, the app's own sounds. */
    var volume: Int
        get() = p.getInt("volume", 80)
        set(v) = p.edit().putInt("volume", v).apply()

    /** Live and Today lists: "mine" (favourite teams + pinned), "comps" (followed competitions) or "all". */
    var matchScope: String
        get() = p.getString("matchScope", "mine") ?: "mine"
        set(v) = p.edit().putString("matchScope", v).apply()

    /** The competition open in the Matches → Competitions chip. */
    var compPick: Int
        get() = p.getInt("compPick", 0)
        set(v) = p.edit().putInt("compPick", v).apply()

    var comps: List<TeamOption>
        get() = readList("comps") ?: DEFAULT_COMPS
        set(v) = writeList("comps", v)

    /** Notification inspector (beta): logs the ongoing notifications of chosen apps, for 1.6. */
    var inspector: Boolean
        get() = p.getBoolean("inspector", false)
        set(v) = p.edit().putBoolean("inspector", v).apply()

    var inspectorApps: Set<String>
        get() = p.getStringSet("inspectorApps", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("inspectorApps", v).apply()

    /** The always-on notch's 8 shortcut slots, as JSON (see Shortcuts). Empty at first. */
    var shortcutsRaw: String
        get() = p.getString("shortcuts", "[]") ?: "[]"
        set(v) = p.edit().putString("shortcuts", v).apply()

    /** Latest headlines about the favourite teams, newest first (the home screen's list). */
    val newsLog: List<NewsItem>
        get() = try {
            val a = JSONArray(p.getString("newsLog", "[]"))
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                NewsItem(o.getString("team"), o.getString("logo"), o.getString("title"), o.getString("url"), o.getLong("at"))
            }
        } catch (e: Exception) {
            emptyList()
        }

    fun addNews(items: List<NewsItem>) {
        val all = (items + newsLog).distinctBy { it.url }.sortedByDescending { it.at }.take(30)
        val a = JSONArray()
        for (n in all) a.put(JSONObject().put("team", n.team).put("logo", n.logo).put("title", n.title).put("url", n.url).put("at", n.at))
        p.edit().putString("newsLog", a.toString()).apply()
    }

    private fun readList(key: String): List<TeamOption>? {
        val raw = p.getString(key, null) ?: return null
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                TeamOption(o.getInt("id"), o.getString("name"), o.getString("logo"))
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeList(key: String, v: List<TeamOption>) {
        val a = JSONArray()
        for (t in v) a.put(JSONObject().put("id", t.id).put("name", t.name).put("logo", t.logo))
        p.edit().putString(key, a.toString()).apply()
    }

    /** Size in percent (50..140). */
    var scale: Int
        get() = p.getInt("scale", 100)
        set(v) = p.edit().putInt("scale", v).apply()

    /** Show the notch over full-screen apps (games, video). */
    var overFullscreen: Boolean
        get() = p.getBoolean("overFullscreen", true)
        set(v) = p.edit().putBoolean("overFullscreen", v).apply()

    var vibrate: Boolean
        get() = p.getBoolean("vibrate", true)
        set(v) = p.edit().putBoolean("vibrate", v).apply()

    /** Swiped away: only a small mark by the camera until swiped back. */
    var tucked: Boolean
        get() = p.getBoolean("tucked", false)
        set(v) = p.edit().putBoolean("tucked", v).apply()

    /** Matches pinned to the notch (any team). Removed when they end. */
    var pinned: Set<Int>
        get() = (p.getStringSet("pinned", emptySet()) ?: emptySet()).mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = p.edit().putStringSet("pinned", v.map { it.toString() }.toSet()).apply()

    /** "system", "ar" or "en". */
    var lang: String
        get() = p.getString("lang", "system") ?: "system"
        set(v) = p.edit().putString("lang", v).apply()

    /** "system", "light" or "dark" (the app's screens; the notch stays black). */
    var theme: String
        get() = p.getString("theme", "system") ?: "system"
        set(v) = p.edit().putString("theme", v).apply()

    /** "fast" (6 s), "balanced" (15 s) or "saver" (30 s). */
    var speed: String
        get() = p.getString("speed", "fast") ?: "fast"
        set(v) = p.edit().putString("speed", v).apply()

    var autoSaver: Boolean
        get() = p.getBoolean("autoSaver", true)
        set(v) = p.edit().putBoolean("autoSaver", v).apply()

    var mobileBalanced: Boolean
        get() = p.getBoolean("mobileBalanced", false)
        set(v) = p.edit().putBoolean("mobileBalanced", v).apply()

    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) = p.edit().putBoolean("onboarded", v).apply()

    /** Full-time score card notification. */
    var cards: Boolean
        get() = p.getBoolean("cards", true)
        set(v) = p.edit().putBoolean("cards", v).apply()

    /** Phone notifications in the notch (beta, off by default). */
    var phone: Boolean
        get() = p.getBoolean("phone", false)
        set(v) = p.edit().putBoolean("phone", v).apply()

    var phoneApps: Set<String>
        get() = p.getStringSet("phoneApps", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("phoneApps", v).apply()

    var phoneHide: Boolean
        get() = p.getBoolean("phoneHide", false)
        set(v) = p.edit().putBoolean("phoneHide", v).apply()

    var phoneFullOnly: Boolean
        get() = p.getBoolean("phoneFullOnly", false)
        set(v) = p.edit().putBoolean("phoneFullOnly", v).apply()

    /** Last run of matches: data in bytes and battery drop (-1 unknown, -2 charging). */
    var lastBytes: Long
        get() = p.getLong("lastBytes", -1)
        set(v) = p.edit().putLong("lastBytes", v).apply()

    var lastBattery: Int
        get() = p.getInt("lastBattery", -1)
        set(v) = p.edit().putInt("lastBattery", v).apply()

    /** Data per day, "yyyy-MM-dd" → bytes, last 14 days. */
    var daily: Map<String, Long>
        get() = try {
            val o = JSONObject(p.getString("daily", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.getLong(it) }
        } catch (e: Exception) {
            emptyMap()
        }
        set(v) {
            val o = JSONObject()
            v.entries.sortedBy { it.key }.takeLast(14).forEach { o.put(it.key, it.value) }
            p.edit().putString("daily", o.toString()).apply()
        }

    /** "Last match: X MB, battery −Y%". */
    var report: String
        get() = p.getString("report", "") ?: ""
        set(v) = p.edit().putString("report", v).apply()

    var lastUpdateCheck: Long
        get() = p.getLong("lastUpdateCheck", 0)
        set(v) = p.edit().putLong("lastUpdateCheck", v).apply()

    /** Favourite teams, highest priority first. */
    var teams: List<TeamOption>
        get() = readList("teams") ?: DEFAULT_TEAMS
        set(v) = writeList("teams", v)

    companion object {
        val DEFAULT_TEAMS = listOf(
            TeamOption(8200, "الأهلي", Api.crest(8200, 20)),
            TeamOption(132, "برشلونة", Api.crest(132)),
            TeamOption(131, "ريال مدريد", Api.crest(131)),
        )

        /** 365Scores ids, checked against its search (2026-11-07). */
        val DEFAULT_COMPS = listOf(
            TeamOption(552, "الدوري المصري", Api.compLogo(552, 131)),
            TeamOption(572, "دوري أبطال أوروبا", Api.compLogo(572, 19)),
            TeamOption(7, "الدوري الإنجليزي", Api.compLogo(7, 1)),
            TeamOption(11, "الدوري الإسباني", Api.compLogo(11, 2)),
            TeamOption(17, "الدوري الإيطالي", Api.compLogo(17, 3)),
            TeamOption(25, "الدوري الألماني", Api.compLogo(25, 4)),
            TeamOption(35, "الدوري الفرنسي", Api.compLogo(35, 5)),
            TeamOption(573, "الدوري الأوروبي", Api.compLogo(573, 19)),
            TeamOption(624, "دوري أبطال أفريقيا", Api.compLogo(624, 44)),
            TeamOption(553, "كأس مصر", Api.compLogo(553, 131)),
            TeamOption(5930, "كأس العالم", Api.compLogo(5930, 54)),
            TeamOption(167, "كأس الأمم الأفريقية", Api.compLogo(167, 44)),
            TeamOption(6316, "كأس الأمم الأوروبية", Api.compLogo(6316, 19)),
        )
    }
}
