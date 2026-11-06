package com.l4.notchlive

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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

    /** Size in percent (80..140). */
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

    var teams: List<TeamOption>
        get() {
            val raw = p.getString("teams", null) ?: return DEFAULT_TEAMS
            return try {
                val a = JSONArray(raw)
                (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    TeamOption(o.getInt("id"), o.getString("name"), o.getString("logo"))
                }
            } catch (e: Exception) {
                DEFAULT_TEAMS
            }
        }
        set(v) {
            val a = JSONArray()
            for (t in v) a.put(JSONObject().put("id", t.id).put("name", t.name).put("logo", t.logo))
            p.edit().putString("teams", a.toString()).apply()
        }

    companion object {
        val DEFAULT_TEAMS = listOf(
            TeamOption(8200, "الأهلي", Api.crest(8200, 20)),
            TeamOption(132, "برشلونة", Api.crest(132)),
            TeamOption(131, "ريال مدريد", Api.crest(131)),
        )
    }
}
