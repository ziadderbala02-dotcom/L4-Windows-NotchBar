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
