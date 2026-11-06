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

/**
 * Downloads the APK straight into an Android install session (no file, no DownloadManager),
 * reporting progress, then hands it to the system installer, which asks the user to confirm.
 */
object Installer {
    /** Runs on a background thread. [progress] gets (bytes so far, total or -1). Throws on failure. */
    fun downloadAndInstall(ctx: android.content.Context, r: Updater.Release, progress: (Long, Long) -> Unit) {
        val pi = ctx.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = pi.createSession(params)
        val session = pi.openSession(id)
        try {
            var url = URL(r.apkUrl)
            var c: HttpURLConnection
            // GitHub sends a redirect to its file host; follow it by hand to be safe.
            var hops = 0
            while (true) {
                c = url.openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "NotchLive")
                val code = c.responseCode
                if (code in 300..399 && hops < 5) {
                    url = URL(url, c.getHeaderField("Location"))
                    c.disconnect()
                    hops++
                    continue
                }
                if (code !in 200..299) throw java.io.IOException("HTTP $code")
                break
            }
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                session.openWrite("NotchLive.apk", 0, if (total > 0) total else -1).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 150) {
                            progress(done, total)
                            last = now
                        }
                    }
                    progress(done, total)
                    session.fsync(out)
                }
            }
            c.disconnect()
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                if (android.os.Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0
            val done = android.app.PendingIntent.getBroadcast(
                ctx, id, android.content.Intent(ctx, InstallReceiver::class.java), flags,
            )
            session.commit(done.intentSender)
            session.close()
        } catch (e: Exception) {
            session.abandon()
            throw e
        }
    }
}

/** The system's answer to the install: show its confirm screen, or tell the app it failed. */
class InstallReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: android.content.Context, i: android.content.Intent) {
        val status = i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999)
        if (status == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = i.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT) ?: return
            confirm.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(confirm)
            } catch (e: Exception) {
                lastError = "مقدرتش أفتح شاشة التسطيب"
            }
        } else if (status != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
            lastError = i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "التسطيب اتلغى أو فشل"
        }
    }

    companion object {
        @Volatile
        var lastError: String? = null
    }
}
