package com.l4.notchlive

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/*
 * 365Scores web API (same one the Windows app uses in src/features/football/s365.ts).
 * statusGroup: 1/2 = not started, 3 = live, 4 = finished. statusId 7 = half time.
 */

data class Side(
    val id: Int,
    val name: String,
    val short: String,
    val logo: String,
    /** Team colour, brightened to glow on black. */
    val color: Int,
    val score: Int,
)

data class Ev(
    /** Stable per match, to spot new events between polls. */
    val id: String,
    /** goal, yellow, red, sub, var, pen, other */
    val kind: String,
    val home: Boolean,
    val minute: String,
    val player: String?,
    /** Assist (goal) or the player going off (substitution). */
    val extra: String?,
    val label: String,
    val own: Boolean,
    val pen: Boolean,
    val at: Double,
)

data class Match(
    val id: Int,
    val comp: String,
    /** Api.PRE / Api.LIVE / Api.POST */
    val state: Int,
    /** "67'", "بين الشوطين", "انتهت", or "" before kickoff. */
    val clock: String,
    /** play / break / var while live. */
    val phase: String,
    val kickoff: Long,
    val home: Side,
    val away: Side,
    val events: List<Ev>,
) {
    fun side(home: Boolean) = if (home) this.home else away
    fun reds(home: Boolean) = events.count { it.kind == "red" && it.home == home }
}

data class TeamOption(val id: Int, val name: String, val logo: String)

data class Stat(val name: String, val home: String, val away: String, val homeShare: Float)

object Api {
    const val PRE = 0
    const val LIVE = 1
    const val POST = 2

    private const val BASE = "https://webws.365scores.com/web"
    private const val PARAMS = "langId=27&timezoneName=Africa/Cairo&userCountryId=131&appTypeId=5"

    private fun url(p: String) = "$BASE/$p${if (p.contains('?')) "&" else "?"}$PARAMS"

    fun crest(id: Int, version: Int = 1) =
        "https://imagecache.365scores.com/image/upload/f_png,w_68,h_68,c_limit,q_auto:eco,dpr_2,d_Competitors:default1.png/v$version/Competitors/$id"

    private fun get(u: String): JSONObject {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36")
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    /** A string field, "" when missing or null. */
    private fun JSONObject.str(key: String): String = if (has(key) && !isNull(key)) optString(key, "") else ""

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    /* ---------- parsing ---------- */

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun parseDate(s: String): Long = try {
        dateFmt.parse(s)?.time ?: 0L
    } catch (e: Exception) {
        0L
    }

    private fun parseColor(s: String): Int? = try {
        if (s.isBlank()) null else Color.parseColor(if (s.startsWith("#")) s else "#$s")
    } catch (e: Exception) {
        null
    }

    /** The team colour, lifted until it reads on black (same idea as glowColor in types.ts). */
    fun glow(main: String, alt: String): Int {
        val lum = { c: Int -> (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255 }
        val a = parseColor(main)
        val b = parseColor(alt)
        var c = listOfNotNull(a, b).maxByOrNull { lum(it) } ?: return Color.WHITE
        var i = 0
        while (lum(c) < 0.45 && i < 8) {
            c = Color.rgb(
                Color.red(c) + ((255 - Color.red(c)) * 0.25).roundToInt(),
                Color.green(c) + ((255 - Color.green(c)) * 0.25).roundToInt(),
                Color.blue(c) + ((255 - Color.blue(c)) * 0.25).roundToInt(),
            )
            i++
        }
        return c
    }

    private fun side(c: JSONObject): Side {
        val id = c.optInt("id")
        val name = c.str("name")
        val score = c.optDouble("score", -1.0)
        return Side(
            id = id,
            name = name,
            short = c.str("shortName").ifBlank { name },
            logo = crest(id, c.optInt("imageVersion", 1)),
            color = glow(c.str("color"), c.str("awayColor")),
            score = if (score.isNaN() || score < 0) 0 else score.toInt(),
        )
    }

    private val HT = Regex("استراحة|بين الشوطين|half ?-?time|\\bHT\\b", RegexOption.IGNORE_CASE)
    private val PENS = Regex("ترجيح|penalt", RegexOption.IGNORE_CASE)
    private val WAIT = Regex("انتظار|قبل|نهاية|awaiting|before|end", RegexOption.IGNORE_CASE)
    private val WAIT2 = Regex("انتظار|نهاية الوقت|awaiting|end of|break", RegexOption.IGNORE_CASE)
    private val EXTRA = Regex("إضافي|extra", RegexOption.IGNORE_CASE)
    private val VAR = Regex("\\bVAR\\b|فار|فيديو", RegexOption.IGNORE_CASE)
    private val PEN = Regex("جزاء|penalt", RegexOption.IGNORE_CASE)

    private fun breakLabel(g: JSONObject): String? {
        val st = "${g.str("statusText")} ${g.str("shortStatusText")}"
        if (g.optInt("statusId") == 7 || HT.containsMatchIn(st)) return "بين الشوطين"
        if (PENS.containsMatchIn(st) && WAIT.containsMatchIn(st)) return "قبل الترجيح"
        if (WAIT2.containsMatchIn(st)) return if (EXTRA.containsMatchIn(st)) "قبل الترجيح" else "قبل الإضافي"
        return null
    }

    fun parse(g: JSONObject): Match {
        val group = g.optInt("statusGroup")
        val state = when (group) {
            3 -> LIVE
            4 -> POST
            else -> PRE
        }
        val pause = if (state == LIVE) breakLabel(g) else null
        val status = "${g.str("statusText")} ${g.str("shortStatusText")}"
        val clock = when {
            state == POST -> "انتهت"
            state != LIVE -> ""
            pause != null -> pause
            else -> g.str("gameTimeDisplay").trim().ifBlank { g.str("shortStatusText").ifBlank { "LIVE" } }
        }
        val phase = when {
            state != LIVE -> ""
            VAR.containsMatchIn(status) -> "var"
            pause != null -> "break"
            else -> "play"
        }
        val home = g.optJSONObject("homeCompetitor") ?: JSONObject()
        val away = g.optJSONObject("awayCompetitor") ?: JSONObject()
        return Match(
            id = g.optInt("id"),
            comp = g.str("competitionDisplayName").split(" - ").first(),
            state = state,
            clock = clock,
            phase = phase,
            kickoff = parseDate(g.str("startTime")),
            home = side(home),
            away = side(away),
            events = events(g, home.optInt("id")),
        )
    }

    private fun events(g: JSONObject, homeId: Int): List<Ev> {
        val names = HashMap<Int, String>()
        for (m in g.optJSONArray("members").objects()) {
            val n = m.str("shortName").ifBlank { m.str("name") }
            if (n.isBlank()) continue
            names[m.optInt("id")] = n
            if (m.has("athleteId")) names[m.optInt("athleteId")] = n
        }
        val name = { id: Int? -> if (id == null) null else names[id] }
        val out = ArrayList<Ev>()
        for ((i, e) in g.optJSONArray("events").objects().withIndex()) {
            val t = e.optJSONObject("eventType") ?: continue
            val typeId = t.optInt("id")
            val sub = t.optInt("subTypeId", -1)
            val label = listOf(t.str("name"), t.str("subTypeName")).filter { it.isNotBlank() && it != "-1" }.joinToString(" - ")
            // 1 goal (sub 2 own goal, 3 penalty), 2 yellow, 3 red, 1000 substitution.
            val kind = when {
                typeId == 1 -> "goal"
                typeId == 2 -> "yellow"
                typeId == 3 -> "red"
                typeId == 1000 -> "sub"
                VAR.containsMatchIn(label) -> "var"
                PEN.containsMatchIn(label) -> "pen"
                else -> "other"
            }
            val gameTime = e.optDouble("gameTime", 0.0).let { if (it.isNaN()) 0.0 else it }
            val shown = e.str("gameTimeDisplay").ifBlank { if (gameTime > 0) "${gameTime.roundToInt()}'" else "" }
            val extra = e.optJSONArray("extraPlayers")?.let { if (it.length() > 0) it.optInt(0) else null }
            out += Ev(
                id = "${e.optInt("order", i)}:$typeId",
                kind = kind,
                home = e.optInt("competitorId") == homeId,
                minute = shown,
                player = name(if (e.has("playerId")) e.optInt("playerId") else null),
                extra = name(extra),
                label = label,
                own = typeId == 1 && sub == 2,
                pen = typeId == 1 && sub == 3,
                at = gameTime + e.optDouble("addedTime", 0.0).let { if (it.isNaN()) 0.0 else it } / 100,
            )
        }
        return out
    }

    /* ---------- requests ---------- */

    /** Recent, live and upcoming games of the given teams, one request for all. */
    fun teamGames(ids: List<Int>): List<Match> {
        if (ids.isEmpty()) return emptyList()
        val r = get(url("games/current/?competitors=${ids.joinToString(",")}"))
        return r.optJSONArray("games").objects().map { parse(it) }
    }

    /** One game with its events (scorers, cards). */
    fun game(id: Int): Match = parse(get(url("game/?gameId=$id")).getJSONObject("game"))

    fun search(q: String): List<TeamOption> {
        val r = get(url("search/?query=${URLEncoder.encode(q, "UTF-8")}"))
        return r.optJSONArray("competitors").objects()
            .filter { it.optInt("sportId") == 1 }
            .take(8)
            .map { TeamOption(it.optInt("id"), it.str("name"), crest(it.optInt("id"), it.optInt("imageVersion", 1))) }
    }

    /** Possession, xG, shots, on target, corners, fouls, offsides, cards, in that order. */
    private val STAT_IDS = listOf(10, 76, 3, 4, 8, 12, 9, 1, 2)

    fun stats(gameId: Int, homeId: Int): List<Stat> {
        val all = get(url("game/stats/?games=$gameId")).optJSONArray("statistics").objects()
        val out = ArrayList<Stat>()
        val num = { v: String -> v.replace("%", "").toFloatOrNull() ?: 0f }
        for (id in STAT_IDS) {
            val rows = all.filter { it.optInt("id") == id }
            if (rows.size < 2) continue
            val h = rows.firstOrNull { it.optInt("competitorId") == homeId } ?: rows[0]
            val a = rows.firstOrNull { it !== h } ?: rows[1]
            val hv = h.str("value").ifBlank { "0" }
            val av = a.str("value").ifBlank { "0" }
            val hp = h.optDouble("valuePercentage", Double.NaN)
            val ap = a.optDouble("valuePercentage", Double.NaN)
            val share = if (!hp.isNaN() && !ap.isNaN() && hp + ap > 0) (hp / (hp + ap)).toFloat()
            else if (num(hv) + num(av) > 0) num(hv) / (num(hv) + num(av)) else 0.5f
            out += Stat(h.str("name"), hv, av, share)
        }
        return out
    }
}
