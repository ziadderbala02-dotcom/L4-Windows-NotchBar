package com.l4.notchlive

import android.content.Context
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** New versions come from this repo's GitHub releases tagged android-vX.Y.Z. */
object Updater {
    private const val RELEASES = "https://api.github.com/repos/ziadderbala02-dotcom/L4-Windows-NotchBar/releases?per_page=30"

    class Release(val version: String, val apkUrl: String)

    fun current(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    /** The newest Android release with an APK, or null. Runs on a background thread. */
    fun latest(): Release? {
        val c = URL(RELEASES).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "NotchLive")
        val text = try {
            if (c.responseCode !in 200..299) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
        val all = JSONArray(text)
        var best: Release? = null
        for (i in 0 until all.length()) {
            val r = all.getJSONObject(i)
            if (r.optBoolean("draft")) continue
            val tag = r.optString("tag_name")
            if (!tag.startsWith("android-v")) continue
            val assets = r.optJSONArray("assets") ?: continue
            var apk: String? = null
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url")
            }
            if (apk == null) continue
            val v = tag.removePrefix("android-v")
            if (best == null || newer(v, best.version)) best = Release(v, apk)
        }
        return best
    }

    /** True when version [a] is above [b] ("1.10.0" > "1.9.2"). */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until max(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    private fun max(a: Int, b: Int) = if (a > b) a else b
}
