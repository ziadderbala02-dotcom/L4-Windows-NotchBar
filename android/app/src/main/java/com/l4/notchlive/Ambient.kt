package com.l4.notchlive

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** A place for the weather and the prayer times. */
data class City(val name: String, val lat: Double, val lon: Double)

/**
 * What the always-on notch can show: the weather (Open-Meteo, free, no account), today's date
 * (with the Hijri date if wanted), the next prayer (worked out on the phone, Egyptian method),
 * and the next match.
 */
object Ambient {
    /** Latest weather: temperature (°C) and the animation kind. */
    @Volatile
    var temp: Int? = null

    @Volatile
    var kind = ""

    /** A clear night (no animation, but a moon and a word). */
    @Volatile
    var night = false

    /** The weather's icon (drawn by WeatherFx.icon) and its word, for the always-on notch. */
    fun glyph(k: String = kind): String = when (k) {
        "sun", "clouds", "rain", "storm", "snow", "fog", "wind" -> k
        else -> if (night) "night" else if (temp != null) "sun" else ""
    }

    fun word(k: String = kind): String = when (k) {
        "" -> L.t("w_clear")
        else -> L.t("w_$k")
    }

    @Volatile
    var fetchedAt = 0L

    private fun getJson(u: String): JSONObject {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "NotchLive")
        try {
            val body = c.inputStream.bufferedReader().readText()
            Api.bytes.addAndGet(body.length.toLong() + 600)
            return JSONObject(body)
        } finally {
            c.disconnect()
        }
    }

    /** Cities matching [q], with region and country to tell them apart. */
    fun searchCities(q: String): List<Pair<City, String>> {
        val lang = if (L.ar) "ar" else "en"
        val r = getJson("https://geocoding-api.open-meteo.com/v1/search?count=10&language=$lang&name=${URLEncoder.encode(q, "UTF-8")}")
        val a = r.optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val where = listOf(o.optString("admin1"), o.optString("country")).filter { it.isNotBlank() }.joinToString(" · ")
            City(o.optString("name"), o.optDouble("latitude"), o.optDouble("longitude")) to where
        }
    }

    /** Fetches the current weather for [city]; true when the animation kind changed. */
    fun refreshWeather(city: City): Boolean {
        val r = getJson(
            "https://api.open-meteo.com/v1/forecast?latitude=${city.lat}&longitude=${city.lon}" +
                "&current=temperature_2m,weather_code,wind_speed_10m,is_day&timezone=auto",
        )
        val cur = r.optJSONObject("current") ?: return false
        temp = Math.round(cur.optDouble("temperature_2m")).toInt()
        val was = kind
        night = cur.optInt("is_day", 1) == 0
        kind = kindOf(cur.optInt("weather_code"), cur.optDouble("wind_speed_10m"), !night)
        fetchedAt = System.currentTimeMillis()
        return was != kind
    }

    /** WMO weather codes to the notch's animations. Clear nights have none. */
    private fun kindOf(code: Int, wind: Double, day: Boolean): String = when (code) {
        in 95..99 -> "storm"
        in 71..77, 85, 86 -> "snow"
        in 51..67, in 80..82 -> "rain"
        45, 48 -> "fog"
        else -> when {
            wind >= 35 -> "wind"
            code in 1..3 -> "clouds"
            code == 0 && day -> "sun"
            else -> ""
        }
    }

    /* ---------- date ---------- */

    /** Short: "السبت ٧" / "Sat 7". */
    fun dateText(now: Long): String {
        val loc = if (L.ar) Locale("ar", "EG") else Locale.ENGLISH
        return java.text.SimpleDateFormat(if (L.ar) "EEEE d" else "EEE d", loc).format(java.util.Date(now))
    }

    fun hijriText(now: Long): String = try {
        val loc = android.icu.util.ULocale(if (L.ar) "ar@calendar=islamic-umalqura" else "en@calendar=islamic-umalqura")
        // "١٦ جمادى": the month's first word keeps it short.
        android.icu.text.SimpleDateFormat("d MMMM", loc).format(java.util.Date(now)).split(" ").take(2).joinToString(" ")
    } catch (e: Exception) {
        ""
    }

    /* ---------- prayer times (the PrayTimes method, Egyptian General Authority angles) ---------- */

    private fun dtr(d: Double) = d * Math.PI / 180.0
    private fun rtd(r: Double) = r * 180.0 / Math.PI
    private fun fixAngle(a: Double) = a - 360.0 * floor(a / 360.0)
    private fun fixHour(a: Double) = a - 24.0 * floor(a / 24.0)

    private fun julian(y: Int, m: Int, d: Int): Double {
        var yy = y
        var mm = m
        if (mm <= 2) {
            yy -= 1
            mm += 12
        }
        val a = floor(yy / 100.0)
        val b = 2 - a + floor(a / 4)
        return floor(365.25 * (yy + 4716)) + floor(30.6001 * (mm + 1)) + d + b - 1524.5
    }

    /** (declination in degrees, equation of time in hours). */
    private fun sun(jd: Double): Pair<Double, Double> {
        val dd = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * dd)
        val q = fixAngle(280.459 + 0.98564736 * dd)
        val l = fixAngle(q + 1.915 * sin(dtr(g)) + 0.020 * sin(dtr(2 * g)))
        val e = 23.439 - 0.00000036 * dd
        val ra = rtd(atan2(cos(dtr(e)) * sin(dtr(l)), cos(dtr(l)))) / 15.0
        val eqt = q / 15.0 - fixHour(ra)
        val decl = rtd(asin(sin(dtr(e)) * sin(dtr(l))))
        return decl to eqt
    }

    /** Today's five prayers as millis (Fajr, Dhuhr, Asr, Maghrib, Isha) for [city]. */
    fun prayers(city: City, dayStart: Calendar): List<Pair<String, Long>> {
        val y = dayStart.get(Calendar.YEAR)
        val m = dayStart.get(Calendar.MONTH) + 1
        val d = dayStart.get(Calendar.DAY_OF_MONTH)
        val jd = julian(y, m, d) - city.lon / (15.0 * 24.0)
        val lat = city.lat
        fun midDay(t: Double) = fixHour(12 - sun(jd + t).second)
        fun angleTime(angle: Double, t: Double, ccw: Boolean): Double {
            val decl = sun(jd + t).first
            val noon = midDay(t)
            val x = (-sin(dtr(angle)) - sin(dtr(decl)) * sin(dtr(lat))) / (cos(dtr(decl)) * cos(dtr(lat)))
            val tt = rtd(acos(x.coerceIn(-1.0, 1.0))) / 15.0
            return noon + if (ccw) -tt else tt
        }
        fun asr(t: Double): Double {
            val decl = sun(jd + t).first
            val angle = -rtd(atan(1.0 / (1.0 + tan(dtr(abs(lat - decl))))))
            return angleTime(angle, t, false)
        }
        val tz = TimeZone.getDefault().getOffset(dayStart.timeInMillis + 12 * 3600_000L) / 3600_000.0
        val adjust = { h: Double -> h + tz - city.lon / 15.0 }
        val raw = listOf(
            "fajr" to angleTime(19.5, 5 / 24.0, true),
            "dhuhr" to midDay(12 / 24.0),
            "asr" to asr(13 / 24.0),
            "maghrib" to angleTime(0.833, 18 / 24.0, false),
            "isha" to angleTime(17.5, 18 / 24.0, false),
        )
        return raw.map { (k, h) -> k to dayStart.timeInMillis + (adjust(h) * 3600_000.0).toLong() }
    }

    /** "العصر بعد ٢٣ د" within the hour, else "العصر ٣:٢٤ م". */
    fun nextPrayerText(city: City, now: Long, timeFormat: java.text.DateFormat): String {
        val day = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        var next = prayers(city, day).firstOrNull { it.second > now }
        if (next == null) {
            day.add(Calendar.DAY_OF_MONTH, 1)
            next = prayers(city, day).first()
        }
        val name = L.t("pr_${next.first}")
        val mins = ((next.second - now) / 60_000L).toInt()
        val t = java.text.SimpleDateFormat("h:mm", if (L.ar) Locale("ar", "EG") else Locale.ENGLISH).format(java.util.Date(next.second))
        return if (mins in 0..59) L.f("pr_in", name, mins) else "$name $t"
    }
}
